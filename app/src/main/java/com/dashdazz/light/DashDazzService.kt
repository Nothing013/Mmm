package com.dashdazz.light

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.Color as AndroidColor
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.sin

data class DashDazzState(
    val isRunning: Boolean = false,
    val mode: Int = DashDazzService.MODE_RAINBOW,
    val color: Int = Color.RED,
    val speedMs: Long = 25L,
    val brightness: Float = 1.0f,
    val screenMode: Int = DashDazzService.SCREEN_ON_ONLY,
    val blinkOnNotification: Boolean = false,
    val isScreenPaused: Boolean = false,
    val palette: Int = Palettes.SPECTRUM,
    val dualStyle: Int = DashDazzService.DUAL_HARDWARE_SPLIT,
    val splitColorA: Int = Color.RED,
    val splitColorB: Int = Color.BLUE,
    val spinColorA: Int = Color.RED,
    val spinColorB: Int = Color.BLUE,
    val duoHueOffset: Int = 90,
    val notifMode: Int = 0,
    val notifActivateDuration: Int = 5,

    val audioReactive: Boolean = false,
    val chargingIndicator: Boolean = false,

    val frameMinMs: Long = 20L
)

class DashDazzService : Service() {

    companion object {
        private const val TAG = "DashDazzService"

        const val ACTION_START = "com.dashdazz.light.action.START"
        const val ACTION_STOP = "com.dashdazz.light.action.STOP"
        const val ACTION_UPDATE = "com.dashdazz.light.action.UPDATE"
        const val ACTION_PULSE = "com.dashdazz.light.action.PULSE"
        const val ACTION_ACTIVATE = "com.dashdazz.light.action.ACTIVATE"

        const val EXTRA_MODE = "extra_mode"
        const val EXTRA_COLOR = "extra_color"
        const val EXTRA_PULSE_HOLD_MS = "extra_pulse_hold_ms"
        const val EXTRA_SPEED_MS = "extra_speed_ms"
        const val EXTRA_BRIGHTNESS = "extra_brightness"
        const val EXTRA_SCREEN_MODE = "extra_screen_mode"
        const val EXTRA_BLINK_NOTIFICATION = "extra_blink_notification"
        const val EXTRA_PALETTE = "extra_palette"
        const val EXTRA_DUAL_STYLE = "extra_dual_style"
        const val EXTRA_SPLIT_COLOR_A = "extra_split_color_a"
        const val EXTRA_SPLIT_COLOR_B = "extra_split_color_b"
        const val EXTRA_SPIN_COLOR_A = "extra_spin_color_a"
        const val EXTRA_SPIN_COLOR_B = "extra_spin_color_b"
        const val EXTRA_DUO_HUE_OFFSET = "extra_duo_hue_offset"
        const val EXTRA_NOTIF_MODE = "extra_notif_mode"
        const val EXTRA_NOTIF_ACTIVATE_DURATION = "extra_notif_activate_duration"
        const val EXTRA_CHARGING_INDICATOR = "extra_charging_indicator"
        const val EXTRA_ACTIVATE_DURATION = "extra_activate_duration"
        const val EXTRA_FORCE = "extra_force"

        const val MODE_RAINBOW = 0
        const val MODE_BREATHE = 1
        const val MODE_SOLID = 2
        const val MODE_DUAL = 3

        const val MODE_AUDIO_REACTIVE = 4

        const val SCREEN_ON_ONLY = 0
        const val SCREEN_OFF_ONLY = 1
        const val SCREEN_ALWAYS = 2

        private const val CHARGING_BREATH_PERIOD_MS = 6000f
        private const val CHARGING_UNKNOWN_HUE = 45f
        private const val LEGACY_MODE_CHARGING = 5
        const val SCREEN_NOTIF_ONLY = 3

        const val DUAL_HARDWARE_SPLIT = 0
        const val DUAL_SPIN = 1
        const val DUAL_POLICE = 2

        const val DUAL_ALTERNATE = 3

        const val DUAL_FIRE_ICE_LEGACY = 4
        const val DUAL_RAINBOW_DUO = 5

        const val DUAL_BEAT_RAINBOW = 6
        const val DUAL_SPLIT_BEAT = 7

        val AUDIO_DUAL_STYLES = intArrayOf(DUAL_BEAT_RAINBOW, DUAL_SPLIT_BEAT)

        const val NOTIFICATION_ID = 101
        const val CHANNEL_ID = "dashdazz_channel"

        private const val NOTIF_TRIGGER_MIN_GAP_MS = 2000L
        @Volatile private var lastPulseMs = 0L
        @Volatile private var lastActivateMs = 0L

        private val _stateFlow = MutableStateFlow(DashDazzState())
        val stateFlow: StateFlow<DashDazzState> = _stateFlow.asStateFlow()

        @Volatile
        private var activeService: DashDazzService? = null

        @Volatile
        private var screenInteractiveFlag = true

        internal fun isScreenInteractive(): Boolean = screenInteractiveFlag

        internal fun onMediaPlaybackChanged(playing: Boolean) {
            val svc = activeService ?: return
            if (!_stateFlow.value.isRunning) return
            if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY && _stateFlow.value.audioReactive) {
                if (playing) {
                    if (_stateFlow.value.isScreenPaused || svc.job?.isActive != true) {
                        Log.d(TAG, "Media playback started in Notif Only mode: waking up animation loop")
                        svc.launchAnimationLoop()
                    }
                }
            }
        }

        internal fun onScreenEvent(context: Context, interactive: Boolean) {
            screenInteractiveFlag = interactive
            val svc = activeService
            val isFlashing = Prefs.isFlashScreen(context)
            if (isFlashing) {
                Log.d(TAG, "Flash on ${if (interactive) "unlock" else "lock"} firing")
                flashScreenEvent(context)
            } else {
                if (svc != null && _stateFlow.value.isRunning) {
                    Log.d(TAG, "Screen ${if (interactive) "ON" else "OFF"} received: applying screen policy")
                    svc.evaluateScreenPolicy()
                }
            }
        }

        internal fun onPowerEvent(context: Context, connected: Boolean) {
            val svc = activeService
            if (!Prefs.isAutoCharging(context)) {
                if (svc != null && svc.stillRunning() && svc.shouldRunNow()) {
                    Log.d(TAG, "Power ${if (connected) "connected" else "disconnected"}: taking the ring back once")
                    svc.launchAnimationLoop()
                }
                return
            }
            if (svc == null) {
                if (connected) {
                    Log.d(TAG, "Power connected: starting charging light from idle")
                    start(context, chargingIndicator = true)
                }
                return
            }
            if (connected) svc.enterChargingMode() else svc.exitChargingMode()
        }

        val isRunning: Boolean
            get() = _stateFlow.value.isRunning

        fun start(
            context: Context,
            mode: Int? = null,
            color: Int? = null,
            speedMs: Long? = null,
            brightness: Float? = null,
            screenMode: Int? = null,
            blinkOnNotification: Boolean? = null,
            palette: Int? = null,
            dualStyle: Int? = null,
            splitColorA: Int? = null,
            splitColorB: Int? = null,
            spinColorA: Int? = null,
            spinColorB: Int? = null,
            duoHueOffset: Int? = null,
            notifMode: Int? = null,
            notifActivateDuration: Int? = null,
            chargingIndicator: Boolean? = null
        ) {

            val s = _stateFlow.value
            val intent = Intent(context, DashDazzService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_CHARGING_INDICATOR, chargingIndicator ?: s.chargingIndicator)
                putExtra(EXTRA_MODE, mode ?: s.mode)
                putExtra(EXTRA_COLOR, color ?: s.color)
                putExtra(EXTRA_SPEED_MS, speedMs ?: s.speedMs)
                putExtra(EXTRA_BRIGHTNESS, brightness ?: s.brightness)
                putExtra(EXTRA_SCREEN_MODE, screenMode ?: s.screenMode)
                putExtra(EXTRA_BLINK_NOTIFICATION, blinkOnNotification ?: s.blinkOnNotification)
                putExtra(EXTRA_PALETTE, palette ?: s.palette)
                putExtra(EXTRA_DUAL_STYLE, dualStyle ?: s.dualStyle)
                putExtra(EXTRA_SPLIT_COLOR_A, splitColorA ?: s.splitColorA)
                putExtra(EXTRA_SPLIT_COLOR_B, splitColorB ?: s.splitColorB)
                putExtra(EXTRA_SPIN_COLOR_A, spinColorA ?: s.spinColorA)
                putExtra(EXTRA_SPIN_COLOR_B, spinColorB ?: s.spinColorB)
                putExtra(EXTRA_DUO_HUE_OFFSET, duoHueOffset ?: s.duoHueOffset)
                putExtra(EXTRA_NOTIF_MODE, notifMode ?: s.notifMode)
                putExtra(EXTRA_NOTIF_ACTIVATE_DURATION, notifActivateDuration ?: s.notifActivateDuration)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, DashDazzService::class.java).apply {
                action = ACTION_STOP
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun setAutoCharging(context: Context, enabled: Boolean) {
            Prefs.setAutoCharging(context, enabled)
            val svc = activeService
            if (!enabled && svc != null && svc.autoChargeActive) {
                svc.exitChargingMode()
            }
        }

        fun updateSettings(
            context: Context,
            mode: Int? = null,
            color: Int? = null,
            speedMs: Long? = null,
            brightness: Float? = null,
            screenMode: Int? = null,
            blinkOnNotification: Boolean? = null,
            palette: Int? = null,
            dualStyle: Int? = null,
            splitColorA: Int? = null,
            splitColorB: Int? = null,
            spinColorA: Int? = null,
            spinColorB: Int? = null,
            duoHueOffset: Int? = null,
            notifMode: Int? = null,
            notifActivateDuration: Int? = null,
            audioReactive: Boolean? = null,
            frameMinMs: Long? = null
        ) {
            val current = _stateFlow.value
            val newMode = mode ?: current.mode
            val newColor = color ?: current.color
            val newSpeed = speedMs ?: current.speedMs
            val newBrightness = brightness ?: current.brightness
            val newScreenMode = screenMode ?: current.screenMode
            val newBlink = blinkOnNotification ?: current.blinkOnNotification
            val newPalette = palette ?: current.palette
            val newDualStyle = dualStyle ?: current.dualStyle
            val newSplitColorA = splitColorA ?: current.splitColorA
            val newSplitColorB = splitColorB ?: current.splitColorB
            val newSpinColorA = spinColorA ?: current.spinColorA
            val newSpinColorB = spinColorB ?: current.spinColorB
            val newDuoHueOffset = duoHueOffset ?: current.duoHueOffset
            val newNotifMode = notifMode ?: current.notifMode
            val newNotifActivateDuration = notifActivateDuration ?: current.notifActivateDuration
            val newAudioReactive = audioReactive ?: current.audioReactive
            val newFrameMinMs = frameMinMs?.coerceIn(15L, 100L) ?: current.frameMinMs

            val modeChanged = (newMode != current.mode)
            val dualStyleChanged = (newDualStyle != current.dualStyle)
            val audioChanged = (newAudioReactive != current.audioReactive)
            val splitColorsChanged = (newSplitColorA != current.splitColorA || newSplitColorB != current.splitColorB)

            val merged = current.copy(
                mode = newMode,
                color = newColor,
                speedMs = newSpeed.coerceIn(10L, 500L),
                brightness = newBrightness,
                screenMode = newScreenMode,
                blinkOnNotification = newBlink,
                palette = newPalette,
                dualStyle = newDualStyle,
                splitColorA = newSplitColorA,
                splitColorB = newSplitColorB,
                spinColorA = newSpinColorA,
                spinColorB = newSpinColorB,
                duoHueOffset = newDuoHueOffset,
                notifMode = newNotifMode,
                notifActivateDuration = newNotifActivateDuration,
                audioReactive = newAudioReactive,
                frameMinMs = newFrameMinMs
            )
            Prefs.saveAll(context, merged)

            _stateFlow.update { merged }

            val svc = activeService
            if (current.isRunning && newMode == MODE_DUAL && newDualStyle == DUAL_HARDWARE_SPLIT && splitColorsChanged) {
                if (svc != null) {
                    svc.ioScope.launch {
                        if (LightsBridge.isRootAvailable()) {
                            LightsBridge.setDualSpotColors(newSplitColorA, newSplitColorB, full = false)
                        }
                    }
                }
            }

            val target = activeService
            if (modeChanged) {
                target?.let { if (it.autoChargeActive) it.autoChargeActive = false }
                if (current.chargingIndicator) {
                    _stateFlow.update { it.copy(chargingIndicator = false) }
                }
            }
            if (target == null && current.isRunning) {

                start(
                    context, mode = newMode, color = newColor, speedMs = newSpeed,
                    brightness = newBrightness, screenMode = newScreenMode,
                    blinkOnNotification = newBlink, palette = newPalette,
                    dualStyle = newDualStyle, splitColorA = newSplitColorA,
                    splitColorB = newSplitColorB, spinColorA = newSpinColorA,
                    spinColorB = newSpinColorB, duoHueOffset = newDuoHueOffset,
                    notifMode = newNotifMode, notifActivateDuration = newNotifActivateDuration
                )
            } else if (current.isRunning &&
                (modeChanged || audioChanged || (newMode == MODE_DUAL && dualStyleChanged))
            ) {
                if (newScreenMode == SCREEN_NOTIF_ONLY && !newAudioReactive) {
                    _stateFlow.update { it.copy(isScreenPaused = true) }
                    target?.pauseAnimationForScreenOff()
                } else {
                    target?.launchAnimationLoop()
                }
                target?.updateNotificationText()
                target?.let { notifyTileState(context) }
            } else if (current.isRunning && newScreenMode != current.screenMode) {
                if (newScreenMode == SCREEN_NOTIF_ONLY && !newAudioReactive) {
                    _stateFlow.update { it.copy(isScreenPaused = true) }
                    target?.pauseAnimationForScreenOff()
                } else {
                    target?.evaluateScreenPolicy()
                }
                target?.updateNotificationText()
                target?.let { notifyTileState(context) }
            }
        }

        fun restoreStateFromPrefs(context: Context) {

            val migratedMode = Prefs.needsAudioModifierMigration(context)
            if (migratedMode != null) {
                Prefs.setMode(context, migratedMode)
                Prefs.setAudioReactive(context, true)
            }

            if (Prefs.getDualStyle(context, -1) == DUAL_FIRE_ICE_LEGACY) {
                Prefs.setDualStyle(context, DUAL_ALTERNATE)
            }

            if (Prefs.getMode(context) == LEGACY_MODE_CHARGING) {
                Log.d(TAG, "Migrating legacy charging mode back to rainbow")
                Prefs.setMode(context, MODE_RAINBOW)
            }

            val saved = Prefs.loadState(context)
            _stateFlow.update { current ->
                current.copy(
                    mode = saved.mode,
                    color = saved.color,
                    speedMs = saved.speedMs,
                    brightness = saved.brightness,
                    screenMode = saved.screenMode,
                    blinkOnNotification = saved.blinkOnNotification,
                    palette = saved.palette,
                    dualStyle = saved.dualStyle,
                    splitColorA = saved.splitColorA,
                    splitColorB = saved.splitColorB,
                    spinColorA = saved.spinColorA,
                    spinColorB = saved.spinColorB,
                    duoHueOffset = saved.duoHueOffset,
                    notifMode = saved.notifMode,
                    notifActivateDuration = saved.notifActivateDuration,
                    audioReactive = saved.audioReactive,
                    frameMinMs = saved.frameMinMs
                )
            }
        }

        fun pulseNotification(context: Context, color: Int, holdMs: Long? = null) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastPulseMs < NOTIF_TRIGGER_MIN_GAP_MS) return
            lastPulseMs = now
            val intent = Intent(context, DashDazzService::class.java).apply {
                action = ACTION_PULSE
                putExtra(EXTRA_COLOR, color)
                putExtra(EXTRA_PULSE_HOLD_MS, holdMs ?: -1L)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun activateForNotification(context: Context, durationSec: Int) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastActivateMs < NOTIF_TRIGGER_MIN_GAP_MS) return
            lastActivateMs = now
            val intent = Intent(context, DashDazzService::class.java).apply {
                action = ACTION_ACTIVATE
                putExtra(EXTRA_ACTIVATE_DURATION, durationSec)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        private fun flashScreenEvent(context: Context) {
            val svc = activeService
            if (svc != null && _stateFlow.value.isRunning) {
                val isNotifOnly = _stateFlow.value.screenMode == SCREEN_NOTIF_ONLY
                val audioReactive = _stateFlow.value.audioReactive
                svc.handleNotificationPulse(Prefs.getColor(context)) {
                    if (isNotifOnly && audioReactive) {
                        // Resume audio-reactive loop after flash
                        svc.evaluateScreenPolicy()
                    } else if (isNotifOnly) {
                        // Pure Notification Only: turn off and return to idle state cleanly
                        LightsBridge.turnOff()
                        _stateFlow.update { it.copy(isRunning = true, isScreenPaused = true) }
                        svc.updateNotificationText()
                        notifyTileState(context)
                    } else {
                        // All other modes: re-apply screen policy
                        svc.evaluateScreenPolicy()
                    }
                }
                return
            }
            val intent = Intent(context, DashDazzService::class.java).apply {
                action = ACTION_PULSE
                putExtra(EXTRA_COLOR, Prefs.getColor(context))
                putExtra(EXTRA_FORCE, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    context.startForegroundService(intent)
                } catch (e: Throwable) {
                    Log.w(TAG, "Flash start deferred: ${e.javaClass.simpleName}")
                    context.startService(intent)
                }
            } else {
                context.startService(intent)
            }
        }

        private fun notifyTileState(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    TileService.requestListeningState(
                        context.applicationContext,
                        ComponentName(context.applicationContext, DashDazzTileService::class.java)
                    )
                } catch (e: Exception) {
                    Log.d(TAG, "Tile state request ignored: ${e.message}")
                }
            }
        }

        fun relaunchAnimation(context: Context) {
            val service = activeService ?: return
            if (!_stateFlow.value.isRunning) return
            service.launchAnimationLoop()
        }

        fun refreshForegroundType(context: Context) {
            val service = activeService ?: return
            if (!_stateFlow.value.isRunning) return
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            try {
                ServiceCompat.startForeground(
                    service,
                    NOTIFICATION_ID,
                    service.buildNotification(),
                    service.foregroundServiceType()
                )
            } catch (e: Exception) {
                Log.d(TAG, "Foreground type refresh ignored: ${e.message}")
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    internal val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val animationMutex = Mutex()
    @Volatile
    private var pulseGen = 0L
    @Volatile
    private var activateGen = 0L
    @Volatile
    private var activateJob: Job? = null
    @Volatile
    private var notifOwnedRun = false
    @Volatile
    private var lastActivateDurationSec = 5
    @Volatile
    private var autoChargeActive = false
    private var autoChargeStartedService = false
    private var job: Job? = null
    private var pulseJob: Job? = null
    @Volatile
    private var ecoOverride = false

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED -> {
                    val saver = (context?.getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode) ?: false
                    if (ecoOverride != saver) {
                        ecoOverride = saver
                        Log.d(TAG, "Battery saver ${if (saver) "on: Eco governor" else "off: restoring governor"}")
                        updateNotificationText()
                    }
                }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        activeService = this
        screenInteractiveFlag =
            (getSystemService(android.os.PowerManager::class.java)?.isInteractive) ?: true
        ecoOverride = (getSystemService(android.os.PowerManager::class.java)?.isPowerSaveMode) ?: false
        createNotificationChannel()

        LightsBridge.logColorLightSurface()

        restoreStateFromPrefs(this)

        ioScope.launch {
            Prefs.migrateLegacyFilters(this@DashDazzService)
            Prefs.migrateLegacyNotificationMode(this@DashDazzService)
            Prefs.migrateLegacyFlashToggles(this@DashDazzService)
        }

        scope.launch {
            runCatching { LightsBridge.turnOff() }
        }

        val filter = IntentFilter().apply {
            addAction(android.os.PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(screenReceiver, filter)
        }

        if (_stateFlow.value.isRunning) evaluateScreenPolicy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent == null || intent.action == null) {
            recoverStickyRestart()
            return START_STICKY
        }
        val action = intent.action

        when (action) {
            ACTION_STOP -> {
                stopLight()
                return START_NOT_STICKY
            }
            ACTION_PULSE -> {
                val force = intent?.getBooleanExtra(EXTRA_FORCE, false) ?: false
                if (!force && Prefs.getNotificationMode(this) == Prefs.NOTIF_MODE_OFF) {
                    stopIfNotForeground()
                    return START_NOT_STICKY
                }
                val pulseColor = intent.getIntExtra(EXTRA_COLOR, _stateFlow.value.color)
                val pulseHold = intent.getLongExtra(EXTRA_PULSE_HOLD_MS, -1L).takeIf { it > 0 }
                handleNotificationPulse(pulseColor, pulseHold)
                return START_NOT_STICKY
            }
            ACTION_ACTIVATE -> {
                if (Prefs.getNotificationMode(this) == Prefs.NOTIF_MODE_OFF) {
                    stopIfNotForeground()
                    return START_NOT_STICKY
                }
                val durationSec = intent.getIntExtra(
                    EXTRA_ACTIVATE_DURATION, Prefs.getNotificationActivateDuration(this)
                ).coerceIn(1, 60)
                handleActivateForNotification(durationSec)
                return START_NOT_STICKY
            }
            ACTION_START, ACTION_UPDATE -> {

                val s = _stateFlow.value
                val newMode = intent.getIntExtra(EXTRA_MODE, s.mode)
                val newCharging = intent.getBooleanExtra(EXTRA_CHARGING_INDICATOR, s.chargingIndicator)
                val newColor = intent.getIntExtra(EXTRA_COLOR, s.color)
                val newSpeed = intent.getLongExtra(EXTRA_SPEED_MS, s.speedMs).coerceIn(10L, 500L)
                val newBrightness = intent.getFloatExtra(EXTRA_BRIGHTNESS, s.brightness)
                val newScreenMode = intent.getIntExtra(EXTRA_SCREEN_MODE, s.screenMode).coerceIn(0, 3)
                val newBlinkNotif = intent.getBooleanExtra(EXTRA_BLINK_NOTIFICATION, s.blinkOnNotification)
                val newPalette = intent.getIntExtra(EXTRA_PALETTE, s.palette)
                val newDualStyle = intent.getIntExtra(EXTRA_DUAL_STYLE, s.dualStyle)
                val newSplitColorA = intent.getIntExtra(EXTRA_SPLIT_COLOR_A, Prefs.getSplitColorA(this))
                val newSplitColorB = intent.getIntExtra(EXTRA_SPLIT_COLOR_B, Prefs.getSplitColorB(this))
                val newSpinColorA = intent.getIntExtra(EXTRA_SPIN_COLOR_A, Prefs.getSpinColorA(this))
                val newSpinColorB = intent.getIntExtra(EXTRA_SPIN_COLOR_B, Prefs.getSpinColorB(this))
                val newDuoHueOffset = intent.getIntExtra(EXTRA_DUO_HUE_OFFSET, Prefs.getDuoHueOffset(this)).coerceIn(0, 360)
                val newNotifMode = intent.getIntExtra(EXTRA_NOTIF_MODE, Prefs.getNotificationMode(this))
                    .coerceIn(Prefs.NOTIF_MODE_OFF, Prefs.NOTIF_MODE_ACTIVATE)
                val newNotifActivateDuration = intent.getIntExtra(EXTRA_NOTIF_ACTIVATE_DURATION, Prefs.getNotificationActivateDuration(this))
                    .coerceIn(1, 60)

                val wasRunning = _stateFlow.value.isRunning
                val modeChanged = (newMode != _stateFlow.value.mode)
                val dualStyleChanged = (newDualStyle != _stateFlow.value.dualStyle)

                Prefs.saveAll(
                    this, _stateFlow.value.copy(
                        mode = newMode, color = newColor, speedMs = newSpeed,
                        brightness = newBrightness, screenMode = newScreenMode,
                        blinkOnNotification = newBlinkNotif, palette = newPalette,
                        dualStyle = newDualStyle, splitColorA = newSplitColorA,
                        splitColorB = newSplitColorB, spinColorA = newSpinColorA,
                        spinColorB = newSpinColorB, duoHueOffset = newDuoHueOffset,
                        notifMode = newNotifMode, notifActivateDuration = newNotifActivateDuration
                    )
                )

                _stateFlow.update {
                    it.copy(
                        isRunning = true,
                        isScreenPaused = false,
                        chargingIndicator = newCharging,
                        mode = newMode,
                        color = newColor,
                        speedMs = newSpeed,
                        brightness = newBrightness,
                        screenMode = newScreenMode,
                        blinkOnNotification = newBlinkNotif,
                        palette = newPalette,
                        dualStyle = newDualStyle,
                        splitColorA = newSplitColorA,
                        splitColorB = newSplitColorB,
                        spinColorA = newSpinColorA,
                        spinColorB = newSpinColorB,
                        duoHueOffset = newDuoHueOffset,
                        notifMode = newNotifMode,
                        notifActivateDuration = newNotifActivateDuration
                    )
                }

                startAsForeground()
                rebindNotificationListener()
                scope.launch {
                    runCatching { LightsBridge.ensureBackgroundSurvival(packageName) }
                }

                if (newCharging) {
                    launchAnimationLoopBypassScreenCheck()
                } else if (newScreenMode == SCREEN_NOTIF_ONLY && !_stateFlow.value.audioReactive) {

                    _stateFlow.update { it.copy(isScreenPaused = true) }
                    pauseAnimationForScreenOff()
                    notifyTileState(this)
                    return START_STICKY
                }

                if (!wasRunning || modeChanged || (newMode == MODE_DUAL && dualStyleChanged) || job?.isActive != true) {
                    launchAnimationLoop()
                } else {
                    evaluateScreenPolicy()
                }
                updateNotificationText()
                notifyTileState(this)
                return START_STICKY
            }
        }

        return START_STICKY
    }

    private fun stopIfNotForeground() {
        if (!_stateFlow.value.isRunning) {
            stopSelf()
        }
    }

    private fun recoverStickyRestart() {
        if (!_stateFlow.value.isRunning) return
        Log.d(TAG, "Sticky restart with no intent: restoring foreground and animation")
        runCatching { startAsForeground() }
        rebindNotificationListener()
        if (autoChargeActive) {
            launchAnimationLoopBypassScreenCheck()
        } else if (shouldRunNow()) {
            launchAnimationLoop()
        } else {
            _stateFlow.update { it.copy(isScreenPaused = true) }
            pauseAnimationForScreenOff()
        }
    }

    private fun startAsForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                foregroundServiceType()
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun rebindNotificationListener() {
        try {
            if (!DashDazzNotificationListener.isNotificationAccessGranted(this)) return
            android.service.notification.NotificationListenerService.requestRebind(
                ComponentName(this, DashDazzNotificationListener::class.java)
            )
        } catch (e: Throwable) {
            Log.d(TAG, "Listener rebind ignored: ${e.message}")
        }
    }

    private fun foregroundServiceType(): Int {
        val special = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return special
        val needsMic = _stateFlow.value.audioReactive &&
            AudioReactiveEngine.audioSource == AudioReactiveEngine.SOURCE_MIC
        return if (needsMic) special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else special
    }

    internal fun updateNotificationText() {
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun pauseAnimationForScreenOff() {
        scope.launch {
            animationMutex.withLock {
                job?.cancelAndJoin()
                job = null
                withContext(NonCancellable) {
                    LightsBridge.turnOff()
                }
            }
        }
        updateNotificationText()
        notifyTileState(this)
    }

    internal fun shouldRunNow(): Boolean = when (_stateFlow.value.screenMode) {
        SCREEN_ON_ONLY -> isScreenInteractive()
        SCREEN_OFF_ONLY -> !isScreenInteractive()
        SCREEN_NOTIF_ONLY -> _stateFlow.value.audioReactive
        else -> true
    }

    internal fun evaluateScreenPolicy() {
        if (!_stateFlow.value.isRunning) return
        if (autoChargeActive) {
            if (job?.isActive != true) launchAnimationLoopBypassScreenCheck()
            return
        }
        if (shouldRunNow()) {
            if (_stateFlow.value.isScreenPaused || job?.isActive != true) {
                launchAnimationLoop()
                updateNotificationText()
            }
        } else if (!_stateFlow.value.isScreenPaused) {
            _stateFlow.update { it.copy(isScreenPaused = true) }
            pauseAnimationForScreenOff()
        }
        notifyTileState(this)
    }

    internal fun launchAnimationLoop() {
        if (!shouldRunNow()) {
            if (!_stateFlow.value.isScreenPaused) {
                _stateFlow.update { it.copy(isScreenPaused = true) }
                pauseAnimationForScreenOff()
            }
            return
        }
        _stateFlow.update { it.copy(isScreenPaused = false) }
        scope.launch {
            animationMutex.withLock {
                job?.cancelAndJoin()
                job = null
                val currentJob = launch {
                    startAudioEngineIfNeeded()
                    try {
                        dispatchAnimationLoop()
                    } finally {
                        AudioReactiveEngine.stop()
                    }
                }
                job = currentJob
            }
        }
        updateNotificationText()
    }

    private suspend fun CoroutineScope.dispatchAnimationLoop() {

        val mode = _stateFlow.value.mode
        if (mode != MODE_DUAL) {

            LightsBridge.resetHardwareToGlobal()
        }
        LightsBridge.resetScene()
        if (_stateFlow.value.chargingIndicator) {
            runChargingLoop()
            return
        }
        when (mode) {
            MODE_RAINBOW -> runRainbowLoop()
            MODE_BREATHE -> runBreatheLoop()
            MODE_SOLID -> runSolidLoop()
            MODE_DUAL -> runDualLoop()
            else -> runRainbowLoop()
        }
    }

    private suspend fun CoroutineScope.startAudioEngineIfNeeded() {
        if (_stateFlow.value.audioReactive) {
            AudioReactiveEngine.loadFromPrefs(this@DashDazzService)
            AudioReactiveEngine.disownPreview()
            AudioReactiveEngine.restart()
        } else {
            AudioReactiveEngine.stop()
        }
    }

    private fun launchAnimationLoopBypassScreenCheck() {
        _stateFlow.update { it.copy(isScreenPaused = false) }
        scope.launch {
            animationMutex.withLock {
                job?.cancelAndJoin()
                job = null
                val currentJob = launch {
                    startAudioEngineIfNeeded()
                    try {
                        dispatchAnimationLoop()
                    } finally {
                        AudioReactiveEngine.stop()
                    }
                }
                job = currentJob
            }
        }
        updateNotificationText()
    }

    private fun reactive(): Boolean = _stateFlow.value.audioReactive

    private fun govDelay(baseMs: Long): Long {
        val floor = if (ecoOverride) Prefs.FRAME_ECO_MS else _stateFlow.value.frameMinMs
        return maxOf(baseMs, floor)
    }

    private suspend fun CoroutineScope.runRainbowLoop() {
        var hue = 0f
        var paletteProgress = 0f
        var audioHue = 0f
        var lastBeatCount = AudioReactiveEngine.beatCountOf()
        val hueIncrement = 1.5f
        var wasSilentOff = false

        while (isActive) {
            val stepMs = _stateFlow.value.speedMs.coerceIn(10L, 250L)
            val brightness = _stateFlow.value.brightness.coerceIn(0.05f, 1f)
            val paletteId = _stateFlow.value.palette
            val palette = Palettes.ALL.firstOrNull { it.id == paletteId } ?: Palettes.ALL[0]

            if (reactive()) {
                val now = System.currentTimeMillis()
                val frame = AudioReactiveEngine.sample(now)
                if (frame.silent) {
                    if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY) {
                        if (!wasSilentOff) {
                            LightsBridge.turnOff()
                            wasSilentOff = true
                        }
                        delay(100L)
                        continue
                    }
                    val dynBright = (brightness * 0.9f).coerceIn(0.05f, 1f)
                    LightsBridge.setCameraRing(Color.HSVToColor(floatArrayOf(hue, 1f, dynBright)))
                    hue = (hue + hueIncrement) % 360f
                    delay(govDelay(stepMs))
                    continue
                }
                if (wasSilentOff) {
                    LightsBridge.ensureHardwareReady()
                    wasSilentOff = false
                }
                val beats = (frame.beatCount - lastBeatCount).toInt()
                if (beats > 0) {
                    lastBeatCount = frame.beatCount
                    audioHue = (audioHue + AudioReactiveEngine.degreesPerBeat * beats.coerceAtMost(3)) % 360f
                }
                val bright = (0.08f + frame.beat * 0.92f) * brightness
                LightsBridge.setCameraRing(hsv(audioHue, 1f, bright))
                delay(govDelay(20L))
                continue
            }

            val userBright = brightness

            when (paletteId) {
                Palettes.SPECTRUM -> {
                    val wave = (sin(hue * Math.PI / 180.0).toFloat() * 0.10f)
                    val dynBright = (userBright * (0.90f + wave)).coerceIn(0.05f, 1f)
                    val dynSat = (0.92f + 0.08f * kotlin.math.cos(hue * Math.PI / 90.0).toFloat()).coerceIn(0.7f, 1f)
                    LightsBridge.setCameraRing(Color.HSVToColor(floatArrayOf(hue, dynSat, dynBright)))
                    hue = (hue + hueIncrement) % 360f
                }
                Palettes.FIRE -> {
                    val colors = palette.colors ?: intArrayOf(0xFFFF0000.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val flicker = (kotlin.random.Random.nextFloat() * 0.28f - 0.14f)
                    val fireBright = (userBright + flicker).coerceIn(0.08f, 1f)
                    val satMod = 0.88f + (kotlin.random.Random.nextFloat() * 0.12f)

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, fireBright, satMod)
                    LightsBridge.setCameraRing(color)

                    val flameAdvance = 0.018f + (kotlin.random.Random.nextFloat() * 0.024f)
                    paletteProgress = (paletteProgress + flameAdvance) % total
                }
                Palettes.SUNSET -> {
                    val colors = palette.colors ?: intArrayOf(0xFFFF1744.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val wave = sin(paletteProgress * Math.PI * 2 / total).toFloat()
                    val dynBright = (userBright * (0.88f + 0.12f * wave)).coerceIn(0.05f, 1f)
                    val dynSat = (0.90f + 0.10f * (1f - wave)).coerceIn(0.75f, 1f)

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, dynBright, dynSat)
                    LightsBridge.setCameraRing(color)
                    paletteProgress = (paletteProgress + 0.025f) % total
                }
                Palettes.OCEAN -> {
                    val colors = palette.colors ?: intArrayOf(0xFF00E5FF.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val wave = sin(paletteProgress * Math.PI * 3 / total).toFloat()
                    val dynBright = (userBright * (0.85f + 0.15f * wave)).coerceIn(0.05f, 1f)
                    val dynSat = (0.88f + 0.12f * (1f - wave)).coerceIn(0.75f, 1f)

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, dynBright, dynSat)
                    LightsBridge.setCameraRing(color)
                    paletteProgress = (paletteProgress + 0.025f) % total
                }
                Palettes.CYBERPUNK -> {
                    val colors = palette.colors ?: intArrayOf(0xFFFF007F.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val surge = if (kotlin.random.Random.nextFloat() > 0.94f) 0.14f else 0.0f
                    val dynBright = (userBright + surge).coerceIn(0.05f, 1f)

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, dynBright, 1.0f)
                    LightsBridge.setCameraRing(color)
                    paletteProgress = (paletteProgress + 0.028f) % total
                }
                Palettes.PASTEL -> {
                    val colors = palette.colors ?: intArrayOf(0xFFFF4081.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val wave = ((sin(paletteProgress * Math.PI * 2 / total) + 1.0) / 2.0).toFloat()
                    val dynBright = (userBright * (0.90f + 0.10f * wave)).coerceIn(0.05f, 1f)
                    val dynSat = (0.72f + 0.22f * wave).coerceIn(0.60f, 0.95f)

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, dynBright, dynSat)
                    LightsBridge.setCameraRing(color)
                    paletteProgress = (paletteProgress + 0.025f) % total
                }
                else -> {
                    val colors = palette.colors ?: intArrayOf(0xFFFF0000.toInt())
                    val total = colors.size
                    val idx1 = paletteProgress.toInt() % total
                    val idx2 = (idx1 + 1) % total
                    val fraction = paletteProgress - paletteProgress.toInt()

                    val color = Palettes.interpolate(colors[idx1], colors[idx2], fraction, userBright)
                    LightsBridge.setCameraRing(color)
                    paletteProgress = (paletteProgress + 0.025f) % total
                }
            }

            delay(govDelay(stepMs))
        }
    }

    private suspend fun CoroutineScope.runBreatheLoop() {
        var angle = 0.0
        var wasSilentOff = false

        while (isActive) {
            val stepMs = _stateFlow.value.speedMs.coerceIn(10L, 250L)
            val baseColor = _stateFlow.value.color
            val hsv = FloatArray(3)
            Color.colorToHSV(baseColor, hsv)
            val userBright = _stateFlow.value.brightness.coerceIn(0.1f, 1f)

            if (!reactive() && LightsBridge.isRootAvailable()) {

                val packed = Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], userBright))
                LightsBridge.setHardwareBreath(
                    Color.red(packed), Color.green(packed), Color.blue(packed)
                )
                _stateFlow.first { s ->
                    s.color != baseColor || s.brightness != userBright ||
                            s.mode != MODE_BREATHE || s.audioReactive
                }
                continue
            }

            if (reactive()) {
                val frame = AudioReactiveEngine.sample(SystemClock.elapsedRealtime())
                if (frame.silent) {
                    if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY) {
                        if (!wasSilentOff) {
                            LightsBridge.turnOff()
                            wasSilentOff = true
                        }
                        delay(100L)
                        continue
                    }
                    val factor = ((sin(angle) + 1.0) / 2.0).toFloat()
                    val fallback = floatArrayOf(hsv[0], hsv[1], (0.05f + factor * (userBright - 0.05f)))
                    LightsBridge.setCameraRing(Color.HSVToColor(fallback))
                    angle += 0.06
                    delay(govDelay(stepMs))
                    continue
                }
                if (wasSilentOff) {
                    LightsBridge.ensureHardwareReady()
                    wasSilentOff = false
                }
                val ceiling = (userBright * (0.35f + 0.65f * frame.level)).coerceIn(0.1f, 1f)
                val centre = 0.05f + (ceiling - 0.05f) * 0.5f
                val depth = ((ceiling - 0.05f) * 0.4f * frame.beat).coerceAtMost(ceiling - centre)
                val maxVal = (centre + (ceiling - centre) * 0.85f + depth).coerceIn(0.1f, 1f)
                val minVal = (centre - depth).coerceAtLeast(0.04f)
                val factor = ((sin(angle) + 1.0) / 2.0).toFloat()
                val breathHsv = floatArrayOf(hsv[0], hsv[1], (minVal + factor * (maxVal - minVal)))
                LightsBridge.setCameraRing(Color.HSVToColor(breathHsv))
                angle += 0.06
                delay(govDelay(20L))
                continue
            }

            val factor = ((sin(angle) + 1.0) / 2.0).toFloat()
            val breathHsv = floatArrayOf(hsv[0], hsv[1], (0.05f + factor * (userBright - 0.05f)))
            LightsBridge.setCameraRing(Color.HSVToColor(breathHsv))
            angle += 0.06
            delay(govDelay(stepMs))
        }
    }

    private suspend fun CoroutineScope.runSolidLoop() {
        var lastColor: Int? = null
        if (reactive()) {
            val baseHsv = FloatArray(3)
            var wasSilentOff = false
            while (isActive) {
                ensureActive()
                val state = _stateFlow.value
                val userBright = state.brightness.coerceIn(0.05f, 1f)
                Color.colorToHSV(state.color, baseHsv)
                val frame = AudioReactiveEngine.sample(SystemClock.elapsedRealtime())
                if (frame.silent) {
                    if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY) {
                        if (!wasSilentOff) {
                            LightsBridge.turnOff()
                            wasSilentOff = true
                            lastColor = null
                        }
                        delay(100L)
                        continue
                    }
                    val targetColor = hsv(baseHsv[0], baseHsv[1], userBright)
                    if (targetColor != lastColor) {
                        lastColor = targetColor
                        LightsBridge.setCameraRing(targetColor)
                    }
                    delay(100L)
                    continue
                }
                if (wasSilentOff) {
                    LightsBridge.ensureHardwareReady()
                    wasSilentOff = false
                    lastColor = null
                }
                val bright = (0.15f + frame.level * 0.70f + frame.beat * 0.30f) * userBright
                val targetColor = hsv(baseHsv[0], baseHsv[1], bright)
                if (targetColor != lastColor) {
                    lastColor = targetColor
                    LightsBridge.setCameraRing(targetColor)
                }
                delay(govDelay(20L))
            }
            return
        }

        _stateFlow.collect { state ->
            ensureActive()
            val hsv = FloatArray(3)
            Color.colorToHSV(state.color, hsv)
            hsv[2] = state.brightness.coerceIn(0.05f, 1f)
            val targetColor = Color.HSVToColor(hsv)
            if (targetColor != lastColor) {
                lastColor = targetColor
                LightsBridge.setCameraRing(targetColor)
            }
        }
    }

    private fun batteryPercent(): Int {
        val bm = getSystemService(BatteryManager::class.java)
        val prop = runCatching { bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }
            .getOrNull()
        if (prop != null && prop in 1..100) return prop
        val sticky = runCatching {
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()
        val level = sticky?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = sticky?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        if (level >= 0 && scale > 0) return ((level * 100f) / scale).toInt().coerceIn(0, 100)
        return -1
    }

    private suspend fun CoroutineScope.runChargingLoop() {
        var lastColor: Int? = null
        var lastPhase = 0f
        var lastLevel = -2
        while (isActive) {
            ensureActive()
            val state = _stateFlow.value
            val userBright = state.brightness.coerceIn(0.05f, 1f)
            val level = batteryPercent()
            if (level != lastLevel) {
                lastLevel = level
                Log.d(TAG, "Charging indicator level=$level")
            }
            val hue = if (level >= 0) level * 1.2f else CHARGING_UNKNOWN_HUE
            val stepMs = govDelay(state.speedMs.coerceIn(10L, 500L))
            val phaseStep = 360f * (stepMs / CHARGING_BREATH_PERIOD_MS)
            lastPhase = (lastPhase + phaseStep) % 360f
            val breathe = 0.65f + 0.35f *
                sin(Math.toRadians(lastPhase.toDouble())).toFloat()
            val targetColor = hsv(hue, 1f, (breathe * userBright).coerceIn(0.15f, 1f))
            if (targetColor != lastColor) {
                lastColor = targetColor
                LightsBridge.setCameraRing(targetColor)
            }
            delay(stepMs)
        }
    }

    private fun hsv(hue: Float, saturation: Float, value: Float): Int {        val h = ((hue % 360f) + 360f) % 360f
        val argb = Color.HSVToColor(floatArrayOf(h, saturation.coerceIn(0f, 1f), value.coerceIn(0.05f, 1f)))
        return if (argb == -1) 0xFFFEFEFE.toInt() else argb
    }

    private val hsvScratch = FloatArray(3)

    private fun hsvFromArgb(argb: Int, brightness: Float): Int {
        AndroidColor.colorToHSV(argb, hsvScratch)
        return hsv(hsvScratch[0], hsvScratch[1], brightness)
    }

    private fun withDim(argb: Int, brightness: Float): Int {
        AndroidColor.colorToHSV(argb, hsvScratch)
        return hsv(hsvScratch[0], hsvScratch[1], (brightness * 0.35f).coerceIn(0.05f, 1f))
    }

    private fun colorToHsv(argb: Int): FloatArray {
        AndroidColor.colorToHSV(argb, hsvScratch)
        return hsvScratch
    }

    private suspend fun CoroutineScope.runDualLoop() {
        var stepIndex = 0
        var subStep = 0
        var lastDualStyle = -1
        var spinInited = false
        var splitInited = false
        var duoHue = 0f
        var beatHue = 0f
        var lastBeatCount = AudioReactiveEngine.beatCountOf()
        var wasSilentOff = false

        while (isActive) {
            val speedMs = _stateFlow.value.speedMs.coerceIn(15L, 500L)
            val brightness = _stateFlow.value.brightness.coerceIn(0.05f, 1f)
            val dualStyle = _stateFlow.value.dualStyle

            if (dualStyle != lastDualStyle) {
                lastDualStyle = dualStyle
                spinInited = false
                splitInited = false
            }

            when (dualStyle) {
                DUAL_HARDWARE_SPLIT -> {
                    if (LightsBridge.isRootAvailable()) {
                        if (!splitInited) {
                            val state = _stateFlow.value
                            LightsBridge.setDualSpotColors(state.splitColorA, state.splitColorB, full = true)
                            splitInited = true
                            delay(400L)
                        } else {

                            val snapA = _stateFlow.value.splitColorA
                            val snapB = _stateFlow.value.splitColorB
                            _stateFlow.first { s ->
                                s.splitColorA != snapA || s.splitColorB != snapB ||
                                        s.dualStyle != DUAL_HARDWARE_SPLIT || s.mode != MODE_DUAL
                            }
                        }
                    } else {
                        LightsBridge.setDualColorGame()
                        delay(3500L)
                    }
                }
                DUAL_SPIN -> {
                    if (LightsBridge.isRootAvailable()) {
                        if (!spinInited) {
                            LightsBridge.initSpinMode()
                            spinInited = true
                        }
                        val isSpotA = (stepIndex % 2 == 0)
                        val activeColor = if (isSpotA) _stateFlow.value.spinColorA else _stateFlow.value.spinColorB
                        LightsBridge.setSpinFrame(isSpotA, activeColor, brightness)
                        stepIndex++
                        val spinDelay = speedMs.coerceIn(50L, 500L)
                        delay(govDelay(spinDelay))
                    } else {
                        val isSpotA = (stepIndex % 2 == 0)
                        val activeColor = if (isSpotA) _stateFlow.value.spinColorA else _stateFlow.value.spinColorB
                        LightsBridge.setCameraRing(activeColor)
                        stepIndex++
                        delay(govDelay(speedMs.coerceIn(80L, 500L)))
                    }
                }
                DUAL_POLICE -> {
                    val red = Color.HSVToColor(floatArrayOf(0f, 1f, brightness))
                    val blue = Color.HSVToColor(floatArrayOf(235f, 1f, brightness))
                    when (subStep % 6) {
                        0, 1 -> LightsBridge.setCameraRing(red)
                        2 -> LightsBridge.setCameraRing(0)
                        3, 4 -> LightsBridge.setCameraRing(blue)
                        5 -> LightsBridge.setCameraRing(0)
                    }
                    subStep++
                    delay(govDelay(speedMs * 3L))
                }
                DUAL_ALTERNATE -> {

                    val state = _stateFlow.value
                    val color0 = hsvFromArgb(state.spinColorA, brightness)
                    val color1 = hsvFromArgb(state.spinColorB, brightness)
                    val activeColor = if (stepIndex % 2 == 0) color0 else color1
                    LightsBridge.setCameraRing(activeColor)
                    stepIndex++
                    delay(govDelay(speedMs * 8L))
                }
                DUAL_RAINBOW_DUO -> {

                    if (LightsBridge.isRootAvailable()) {
                        val offset = _stateFlow.value.duoHueOffset.toFloat()
                        val hueB = ((duoHue + offset) % 360f + 360f) % 360f

                        val colorA = Color.HSVToColor(floatArrayOf(duoHue, 1f, brightness))
                        val colorB = Color.HSVToColor(floatArrayOf(hueB, 1f, brightness))
                        val needFull = !splitInited
                        LightsBridge.setDualSpotColors(colorA, colorB, full = needFull)
                        splitInited = true
                        duoHue = (duoHue + 3f) % 360f
                        delay(govDelay(speedMs.coerceIn(30L, 250L)))
                    } else {
                        LightsBridge.setDualColorGame()
                        delay(3500L)
                    }
                }
                DUAL_BEAT_RAINBOW -> {

                    val frame = AudioReactiveEngine.sample(SystemClock.elapsedRealtime())
                    if (frame.silent) {
                        if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY) {
                            if (!wasSilentOff) {
                                LightsBridge.turnOff()
                                wasSilentOff = true
                                splitInited = false
                            }
                            delay(100L)
                            continue
                        }

                        beatHue = (beatHue + 1.5f) % 360f
                        val bright = (brightness * 0.9f).coerceIn(0.05f, 1f)
                        val colorA = hsv(beatHue, 1f, bright)
                        if (LightsBridge.isRootAvailable()) {
                            val needFull = !splitInited
                            val colorB = hsv(beatHue + _stateFlow.value.duoHueOffset, 1f, bright)
                            LightsBridge.setDualSpotColors(colorA, colorB, full = needFull)
                            splitInited = true
                        } else {
                            LightsBridge.setCameraRing(colorA)
                        }
                        delay(govDelay(speedMs.coerceIn(30L, 250L)))
                    } else {
                        if (wasSilentOff) {
                            LightsBridge.ensureHardwareReady()
                            wasSilentOff = false
                            splitInited = false
                        }
                        val beats = (frame.beatCount - lastBeatCount).toInt()
                        if (beats > 0) {
                            lastBeatCount = frame.beatCount
                            beatHue = (beatHue + AudioReactiveEngine.degreesPerBeat * beats.coerceAtMost(3)) % 360f
                        }
                        val bright = (0.08f + frame.beat * 0.92f) * brightness
                        val colorA = hsv(beatHue, 1f, bright)
                        val colorB = hsv(beatHue + _stateFlow.value.duoHueOffset, 1f, bright)
                        if (LightsBridge.isRootAvailable()) {
                            val needFull = !splitInited
                            LightsBridge.setDualSpotColors(colorA, colorB, full = needFull)
                            splitInited = true
                        } else {
                            LightsBridge.setCameraRing(colorA)
                        }
                        delay(govDelay(20L))
                    }
                }
                DUAL_SPLIT_BEAT -> {

                    val frame = AudioReactiveEngine.sample(SystemClock.elapsedRealtime())
                    if (frame.silent) {
                        if (_stateFlow.value.screenMode == SCREEN_NOTIF_ONLY) {
                            if (!wasSilentOff) {
                                LightsBridge.turnOff()
                                wasSilentOff = true
                                splitInited = false
                            }
                            delay(100L)
                            continue
                        }

                        val state = _stateFlow.value
                        val dimA = withDim(state.splitColorA, brightness)
                        val dimB = withDim(state.splitColorB, brightness)
                        if (LightsBridge.isRootAvailable()) {
                            val needFull = !splitInited
                            LightsBridge.setDualSpotColors(dimA, dimB, full = needFull)
                            splitInited = true
                        } else {
                            LightsBridge.setCameraRing(dimA)
                        }
                        delay(100L)
                    } else {
                        if (wasSilentOff) {
                            LightsBridge.ensureHardwareReady()
                            wasSilentOff = false
                            splitInited = false
                        }
                        val even = frame.beatCount % 2 == 0L
                        val state = _stateFlow.value
                        val brightA = (0.10f + (if (even) frame.beat else frame.level) * 0.9f) * brightness
                        val brightB = (0.10f + (if (even) frame.level else frame.beat) * 0.9f) * brightness
                        val colorA = hsv(colorToHsv(state.splitColorA)[0], 1f, brightA)
                        val colorB = hsv(colorToHsv(state.splitColorB)[0], 1f, brightB)
                        if (LightsBridge.isRootAvailable()) {
                            val needFull = !splitInited
                            LightsBridge.setDualSpotColors(colorA, colorB, full = needFull)
                            splitInited = true
                        } else {
                            LightsBridge.setCameraRing(colorA)
                        }
                        delay(govDelay(20L))
                    }
                }
                else -> {
                    LightsBridge.setDualColorGame()
                    delay(3500L)
                }
            }
        }
    }

    private fun handleNotificationPulse(pulseColor: Int, holdMs: Long? = null, onFinish: (() -> Unit)? = null) {
        val gen = ++pulseGen

        activateGen++
        activateJob?.cancel()
        val wasRunningBeforePulse = _stateFlow.value.isRunning && !_stateFlow.value.isScreenPaused
        val wasPausedByPolicy = _stateFlow.value.isRunning && _stateFlow.value.isScreenPaused
        val isNotifOnlyMode = _stateFlow.value.screenMode == SCREEN_NOTIF_ONLY

        if (!_stateFlow.value.isRunning) {
            startAsForeground()
        }

        pulseJob?.cancel()
        pulseJob = scope.launch {
            try {
                animationMutex.withLock {
                    if (gen != pulseGen) return@withLock
                    job?.cancelAndJoin()
                    job = null
                }

                LightsBridge.ensureHardwareReady()

                val hsv = FloatArray(3)
                Color.colorToHSV(pulseColor, hsv)
                val maxBright = _stateFlow.value.brightness.coerceIn(0.1f, 1f)

                if (holdMs != null && holdMs > 0) {
                    val fade = (holdMs / 4).coerceIn(100L, 500L)
                    val inSteps = 15
                    val stepDelay = maxOf(1L, fade / inSteps)
                    for (i in 0..inSteps) {
                        ensureActive()
                        if (gen != pulseGen) return@launch
                        val f = i.toFloat() / inSteps
                        LightsBridge.setCameraRing(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], f * maxBright)))
                        delay(stepDelay)
                    }
                    delay((holdMs - 2 * fade).coerceAtLeast(0L))
                    for (i in inSteps downTo 0) {
                        ensureActive()
                        if (gen != pulseGen) return@launch
                        val f = i.toFloat() / inSteps
                        LightsBridge.setCameraRing(Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], f * maxBright)))
                        delay(stepDelay)
                    }
                } else {
                    for (cycle in 0 until 2) {
                        ensureActive()
                        if (gen != pulseGen) return@launch
                        val steps = 25
                        for (i in 0..steps) {
                            ensureActive()
                            val progress = sin(Math.PI * i / steps).toFloat()
                            val pulseHsv = floatArrayOf(hsv[0], hsv[1], progress * maxBright)
                            LightsBridge.setCameraRing(Color.HSVToColor(pulseHsv))
                            delay(18)
                        }
                        delay(60)
                    }
                }
            } finally {
                if (gen != pulseGen) return@launch
                withContext(NonCancellable) {
                    animationMutex.withLock {
                        if (gen != pulseGen) return@withLock
                        LightsBridge.resetScene()
                        if (onFinish != null) {
                            onFinish.invoke()
                        } else {
                            when {
                                wasRunningBeforePulse -> {
                                    if (notifOwnedRun) {
                                        launchAnimationLoopBypassScreenCheck()
                                        armActivateExpiry(lastActivateDurationSec, isNotifOnlyMode)
                                    } else {
                                        launchAnimationLoop()
                                    }
                                }
                                wasPausedByPolicy || (isNotifOnlyMode && !_stateFlow.value.audioReactive) -> {

                                    LightsBridge.turnOff()
                                    notifOwnedRun = false
                                    _stateFlow.update { it.copy(isRunning = true, isScreenPaused = true) }
                                    updateNotificationText()
                                    notifyTileState(this@DashDazzService)
                                }
                                isNotifOnlyMode && _stateFlow.value.audioReactive -> {
                                    evaluateScreenPolicy()
                                }
                                else -> {
                                    LightsBridge.turnOff()
                                    notifOwnedRun = false
                                    stopLight()
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun handleActivateForNotification(durationSec: Int) {
        val safeDuration = durationSec.coerceIn(1, 60)
        pulseGen++
        pulseJob?.cancel()

        if (stillRunning() && !notifOwnedRun) {
            val gen = ++activateGen
            activateJob?.cancel()
            activateJob = scope.launch {
                try {
                    delay(safeDuration * 1000L)
                } finally {
                    if (gen != activateGen) return@launch
                    withContext(NonCancellable) {
                        Log.d(TAG, "Notification activate duration expired")
                        updateNotificationText()
                    }
                }
            }
            updateNotificationText()
            return
        }

        val savedMode = Prefs.getMode(this)
        val savedColor = Prefs.getColor(this)
        val savedSpeed = Prefs.getSpeedMs(this)
        val savedBrightness = Prefs.getBrightness(this)
        val savedScreenMode = Prefs.getScreenMode(this)
        val savedPalette = Prefs.getPalette(this)
        val savedDualStyle = Prefs.getDualStyle(this)
        val savedSplitColorA = Prefs.getSplitColorA(this)
        val savedSplitColorB = Prefs.getSplitColorB(this)
        val savedSpinColorA = Prefs.getSpinColorA(this)
        val savedSpinColorB = Prefs.getSpinColorB(this)
        val savedDuoHueOffset = Prefs.getDuoHueOffset(this)
        val savedNotifMode = Prefs.getNotificationMode(this)
        val savedNotifActivateDuration = Prefs.getNotificationActivateDuration(this)
        val savedAudioReactive = Prefs.isAudioReactive(this)

        _stateFlow.update {
            it.copy(
                isRunning = true,
                isScreenPaused = false,
                mode = savedMode,
                color = savedColor,
                speedMs = savedSpeed,
                brightness = savedBrightness,
                screenMode = savedScreenMode,
                blinkOnNotification = it.blinkOnNotification,
                palette = savedPalette,
                dualStyle = savedDualStyle,
                splitColorA = savedSplitColorA,
                splitColorB = savedSplitColorB,
                spinColorA = savedSpinColorA,
                spinColorB = savedSpinColorB,
                duoHueOffset = savedDuoHueOffset,
                notifMode = savedNotifMode,
                notifActivateDuration = savedNotifActivateDuration,
                audioReactive = savedAudioReactive
            )
        }

        startAsForeground()

        val isNotifOnlyMode = savedScreenMode == SCREEN_NOTIF_ONLY
        if (!isNotifOnlyMode) {
            launchAnimationLoop()
        } else {

            launchAnimationLoopBypassScreenCheck()
        }
        notifyTileState(this)
        updateNotificationText()

        notifOwnedRun = true
        lastActivateDurationSec = safeDuration
        armActivateExpiry(safeDuration, isNotifOnlyMode)
    }

    private fun armActivateExpiry(safeDuration: Int, isNotifOnlyMode: Boolean) {
        val gen = ++activateGen
        activateJob?.cancel()
        activateJob = scope.launch {
            try {
                delay(safeDuration * 1000L)
            } finally {
                if (gen != activateGen) return@launch
                withContext(NonCancellable) {
                    animationMutex.withLock {
                        if (gen != activateGen) return@withLock
                        if (isNotifOnlyMode) Log.d(TAG, "Notif Only activation window ended")
                        Log.d(TAG, "Notification activate duration expired, stopping")
                        notifOwnedRun = false
                        stopLight()
                    }
                }
            }
        }
    }

    internal fun enterChargingMode() {
        if (autoChargeActive) return
        val s = _stateFlow.value
        autoChargeStartedService = !s.isRunning
        autoChargeActive = true
        if (s.isRunning) {
            _stateFlow.update { it.copy(chargingIndicator = true, isScreenPaused = false) }
            launchAnimationLoopBypassScreenCheck()
            updateNotificationText()
            notifyTileState(this)
        } else {
            start(this, chargingIndicator = true)
        }
    }

    internal fun exitChargingMode() {
        val startedByUs = autoChargeStartedService
        autoChargeStartedService = false
        val wasActive = autoChargeActive
        autoChargeActive = false
        if (!wasActive) {
            if (stillRunning() && shouldRunNow()) {
                Log.d(TAG, "Power disconnected: taking the ring back once")
                launchAnimationLoop()
            }
            return
        }
        _stateFlow.update { it.copy(chargingIndicator = false) }
        if (startedByUs) {
            Log.d(TAG, "Charging indicator ended: stopping the light we started")
            stopLight()
        } else {
            Log.d(TAG, "Charging indicator ended: restoring the configured mode")
            evaluateScreenPolicy()
        }
    }

    internal fun stillRunning(): Boolean =
        _stateFlow.value.isRunning && !_stateFlow.value.isScreenPaused

    private fun stopLight() {
        pulseGen++
        activateGen++
        notifOwnedRun = false
        pulseJob?.cancel()
        activateJob?.cancel()
        scope.launch {
            animationMutex.withLock {
                job?.cancelAndJoin()
                job = null
                AudioReactiveEngine.stop()
                withContext(NonCancellable) {
                    LightsBridge.turnOff()
                }
            }
        }

        _stateFlow.update { it.copy(isRunning = false, isScreenPaused = false) }
        updateNotificationText()
        notifyTileState(this)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Thread {
            try {
                LightsBridge.turnOff()
            } catch (_: Throwable) {
            }
        }.apply { isDaemon = true }.start()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        if (activeService == this) {
            activeService = null
        }
        pulseGen++
        activateGen++
        try {
            unregisterReceiver(screenReceiver)
        } catch (e: Exception) {
            Log.w(TAG, "Unregistering screenReceiver failed", e)
        }

        pulseJob?.cancel()
        activateJob?.cancel()
        val loop = job
        job = null
        _stateFlow.update { it.copy(isRunning = false, isScreenPaused = false) }
        ioScope.launch {
            loop?.cancelAndJoin()
            withContext(NonCancellable) {
                runCatching { LightsBridge.turnOff() }
            }
            ioScope.cancel()
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            if (mgr?.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = getString(R.string.notification_channel_desc)
                    setShowBadge(false)
                }
                mgr?.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, DashDazzService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val openActivityIntent = Intent(this, MainActivity::class.java)
        val openActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            openActivityIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )

        val state = _stateFlow.value
        val modeName = if (state.chargingIndicator) {
            getString(R.string.mode_charging)
        } else when (state.mode) {
            MODE_RAINBOW -> getString(R.string.mode_rainbow)
            MODE_BREATHE -> getString(R.string.mode_breathe)
            MODE_SOLID -> getString(R.string.mode_solid)
            MODE_DUAL -> getString(R.string.mode_dual)
            else -> getString(R.string.mode_rainbow)
        }

        val audioLabel = if (state.audioReactive) " ${getString(R.string.notif_audio_suffix)}" else ""
        val labelledMode = modeName + audioLabel
        val activateLabel = getString(R.string.notif_activate_running)
        val baseText = if (activateJob?.isActive == true) "$activateLabel: $labelledMode" else labelledMode

        val contentText = if (state.isScreenPaused) {
            if (state.screenMode == SCREEN_NOTIF_ONLY) {
                getString(R.string.notification_notif_only)
            } else {
                getString(R.string.notification_paused)
            }
        } else if (state.screenMode == SCREEN_NOTIF_ONLY && state.audioReactive) {
            "${getString(R.string.screen_mode_notif_only)}: $labelledMode"
        } else {
            baseText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_qs_camera_ring)
            .setContentIntent(openActivityPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                0,
                getString(R.string.action_turn_off),
                stopPendingIntent
            )
            .build()
    }
}
