package com.dashdazz.light

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import java.io.File
import rikka.shizuku.Shizuku
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.extra.*
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme

class MainActivity : ComponentActivity() {

    private val permListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        shizukuPermissionState.value = LightsBridge.hasShizukuPermission()
        if (requestCode == 1001 && grantResult != PackageManager.PERMISSION_GRANTED) {
            runOnUiThread {
                Toast.makeText(this, R.string.shizuku_permission_needed, Toast.LENGTH_LONG).show()
            }
        }
    }

    private val shizukuPermissionState = mutableStateOf(false)
    private val conflictState = mutableStateOf(false)
    private val darkModeState = mutableIntStateOf(0)
    private val rootState = mutableStateOf(false)
    private val rootCheckedState = mutableStateOf(false)
    private val setupDoneState = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        darkModeState.intValue = Prefs.getDarkMode(this)
        setupDoneState.value = Prefs.isSetupDone(this)

        DashDazzService.restoreStateFromPrefs(this)

        applyNightMode(darkModeState.intValue)

        shizukuPermissionState.value = LightsBridge.hasShizukuPermission()
        Shizuku.addRequestPermissionResultListener(permListener)
        checkNotificationPermission()

        checkRootStatusAsync()
        Thread {
            if (!DashDazzService.isRunning) {
                runCatching { LightsBridge.turnOff() }
            }
        }.start()

        setContent {
            val systemDark = isSystemInDarkTheme()
            val isDark = when (darkModeState.intValue) {
                1 -> false
                2 -> true
                else -> systemDark
            }
            val colors = if (isDark) darkColorScheme() else lightColorScheme()

            MiuixTheme(colors = colors) {
                if (!setupDoneState.value) {
                    SetupScreen(
                        isRootAvailable = rootState.value,
                        rootChecked = rootCheckedState.value,
                        hasShizukuPermission = shizukuPermissionState.value,
                        onRecheckRoot = { checkRootStatusAsync() },
                        onRequestShizukuPermission = {
                            if (LightsBridge.isShizukuAvailable()) {
                                Shizuku.requestPermission(1001)
                            } else {
                                openShizukuManager()
                            }
                        },
                        onContinue = {
                            Prefs.setSetupDone(this@MainActivity, true)
                            setupDoneState.value = true
                        }
                    )
                } else {
                    DashDazzScreen(
                        hasShizukuPermission = shizukuPermissionState.value,
                        isRootAvailable = rootState.value,
                        rootChecked = rootCheckedState.value,
                        hasConflict = conflictState.value,
                        selectedDarkMode = darkModeState.intValue,
                        onDarkModeChange = { modeVal ->
                            darkModeState.intValue = modeVal
                            Prefs.setDarkMode(this@MainActivity, modeVal)
                            applyNightMode(modeVal)
                        },
                        onRequestShizukuPermission = {
                            if (LightsBridge.isShizukuAvailable()) {
                                Shizuku.requestPermission(1001)
                            } else {
                                openShizukuManager()
                            }
                        },
                        onRecheckRoot = { checkRootStatusAsync() }
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        shizukuPermissionState.value = LightsBridge.hasShizukuPermission()
        conflictState.value = ConflictDetector.isStockMusicLightActive(this)
        checkRootStatusAsync()
        resumePendingUpdateInstall()
        applyPendingNotifMode()
    }

    private fun applyPendingNotifMode() {
        val pending = Prefs.getPendingNotifMode(this) ?: return
        if (!DashDazzNotificationListener.isNotificationAccessGranted(this)) return
        Prefs.setPendingNotifMode(this, null)
        DashDazzService.updateSettings(this, notifMode = pending)
    }

    private fun resumePendingUpdateInstall() {
        val path = Prefs.getPendingUpdateApk(this) ?: return
        val file = File(path)
        if (!file.exists()) {
            Prefs.setPendingUpdateApk(this, null)
            return
        }
        if (!UpdateChecker.canInstallPackages(this)) return
        Prefs.setPendingUpdateApk(this, null)
        runCatching { startActivity(UpdateChecker.installIntent(this, file)) }
    }

    @Volatile
    private var rootCheckSeq = 0

    private fun checkRootStatusAsync() {
        val seq = ++rootCheckSeq
        Thread {
            val root = LightsBridge.checkRootFresh()

            if (seq != rootCheckSeq) return@Thread
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (seq != rootCheckSeq) return@runOnUiThread
                rootState.value = root
                rootCheckedState.value = true
            }
        }.start()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        super.onDestroy()
    }

    private fun checkNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    1002
                )
            }
        }
    }

    companion object {
        fun applyNightMode(mode: Int) {
            val target = when (mode) {
                1 -> AppCompatDelegate.MODE_NIGHT_NO
                2 -> AppCompatDelegate.MODE_NIGHT_YES
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
            AppCompatDelegate.setDefaultNightMode(target)
        }
    }

    private fun openShizukuManager() {
        val pm = packageManager
        val launch = runCatching {
            pm.getLaunchIntentForPackage("moe.shizuku.privileged.api")
        }.getOrNull()
        try {
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launch)
            } else {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=moe.shizuku.privileged.api")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
        } catch (_: Exception) {
            Toast.makeText(this, R.string.shizuku_not_running, Toast.LENGTH_SHORT).show()
        }
    }
}

private fun openStripLightSettings(context: Context) {
    val candidates = listOf(
        Intent("miui.intent.action.STRIP_LIGHT_SETTINGS"),
        Intent("com.miui.intent.action.STRIP_LIGHT_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS)
    )
    for (intent in candidates) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            return
        } catch (_: Exception) {
        }
    }
}

private fun hueDistance(a: Float, b: Float): Float {
    val diff = Math.abs(a - b) % 360f
    return if (diff > 180f) 360f - diff else diff
}

private fun updateStatusLabel(
    status: UpdateChecker.UpdateStatus,
    progress: Int,
    desc: String,
    checking: String,
    install: String,
    upToDate: String,
    retry: String,
    downloadFailed: String,
    rateLimited: String,
    assetPending: String,
    needPermission: String,
    noInstaller: String
): String {
    if (status is UpdateChecker.UpdateStatus.Downloading) {
        return if (progress in 0..100) "$progress%" else "0%"
    }
    if (progress in 0..100) return "$progress%"
    return when (status) {
        is UpdateChecker.UpdateStatus.Idle -> desc
        is UpdateChecker.UpdateStatus.Checking -> checking
        is UpdateChecker.UpdateStatus.UpToDate -> upToDate
        is UpdateChecker.UpdateStatus.Available -> install
        is UpdateChecker.UpdateStatus.Downloading -> checking
        is UpdateChecker.UpdateStatus.NeedsInstallPermission -> needPermission
        is UpdateChecker.UpdateStatus.Failed -> when (status.reason) {
            "download_failed" -> downloadFailed
            "rate_limited" -> rateLimited
            "asset_pending" -> assetPending
            "no_installer" -> noInstaller
            else -> retry
        }
    }
}

@Composable
private fun LiveRingPreview(
    state: DashDazzState,
    baseColor: Int,
    brightness: Float,
    modifier: Modifier = Modifier
) {
    val scratch = remember { FloatArray(3) }

    val live = state.isRunning && !state.isScreenPaused
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state.mode, state.dualStyle, live, state.frameMinMs) {
        tick = 0
        if (!live) return@LaunchedEffect
        val period = maxOf(50L, state.frameMinMs)
        while (true) {
            delay(period)
            tick++
        }
    }
    fun hsvColor(h: Float, s: Float, v: Float): Color {
        val hn = ((h % 360f) + 360f) % 360f
        return Color(AndroidColor.HSVToColor(floatArrayOf(hn, s.coerceIn(0f, 1f), v.coerceIn(0.02f, 1f))))
    }
    fun hueSatOf(argb: Int): Pair<Float, Float> {
        AndroidColor.colorToHSV(argb, scratch)
        return scratch[0] to scratch[1]
    }
    val bright = brightness.coerceIn(0.05f, 1f)
    val t = tick
    val stepMs = state.speedMs.coerceIn(10L, 500L)
    fun ticks(ms: Long): Int = (ms / 50L).toInt().coerceAtLeast(1)

    val rate = 50f / stepMs.toFloat()
    val duoRate = 50f / stepMs.coerceIn(30L, 250L).toFloat()

    val (halfA, halfB) = if (live) {
        Color(LightsBridge.lastHwColorA) to Color(LightsBridge.lastHwColorB)
    } else when {
        state.mode == DashDazzService.MODE_RAINBOW -> {
            val palette = Palettes.ALL.firstOrNull { it.id == state.palette }
            (palette?.colors?.firstOrNull()?.let { Color(it) }
                ?: hsvColor(0f, 1f, bright)).let { it to it }
        }
        state.mode == DashDazzService.MODE_BREATHE -> {
            val (h, s) = hueSatOf(baseColor)
            val factor = ((kotlin.math.sin(t * 0.06f * rate) + 1.0) / 2.0).toFloat()
            hsvColor(h, s, 0.05f + factor * (bright - 0.05f)).let { it to it }
        }
        state.mode == DashDazzService.MODE_SOLID -> {
            Color(baseColor).let { it to it }
        }
        state.mode == DashDazzService.MODE_DUAL &&
                (state.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) -> {
            Color(state.splitColorA) to Color(state.splitColorB)
        }
        state.mode == DashDazzService.MODE_DUAL &&
                (state.dualStyle == DashDazzService.DUAL_SPIN ||
                        state.dualStyle == DashDazzService.DUAL_ALTERNATE) -> {
            val activeFirst = if (state.dualStyle == DashDazzService.DUAL_SPIN) {
                ((t / ticks(stepMs.coerceIn(50L, 500L))) % 2 == 0)
            } else {
                ((t / ticks(stepMs * 8L)) % 2 == 0)
            }
            val (ha, sa) = hueSatOf(state.spinColorA)
            val (hb, sb) = hueSatOf(state.spinColorB)
            if (activeFirst) {
                hsvColor(ha, sa, bright) to hsvColor(hb, sb, 0.08f)
            } else {
                hsvColor(ha, sa, 0.08f) to hsvColor(hb, sb, bright)
            }
        }
        state.mode == DashDazzService.MODE_DUAL && state.dualStyle == DashDazzService.DUAL_POLICE -> {

            when ((t / ticks(stepMs * 3L)) % 6) {
                0, 1 -> hsvColor(0f, 1f, bright).let { it to it }
                3, 4 -> hsvColor(235f, 1f, bright).let { it to it }
                else -> hsvColor(0f, 0f, 0.03f).let { it to it }
            }
        }
        state.mode == DashDazzService.MODE_DUAL && state.dualStyle == DashDazzService.DUAL_RAINBOW_DUO -> {
            val duo = (t * 3f * duoRate) % 360f
            val hueB = ((duo + state.duoHueOffset) % 360f + 360f) % 360f
            hsvColor(duo, 1f, bright) to hsvColor(hueB, 1f, bright)
        }
        state.mode == DashDazzService.MODE_DUAL && state.dualStyle == DashDazzService.DUAL_BEAT_RAINBOW -> {
            val h = (t * 1.5f * rate) % 360f
            val v = bright * 0.9f
            val hueB = ((h + state.duoHueOffset) % 360f + 360f) % 360f
            hsvColor(h, 1f, v) to hsvColor(hueB, 1f, v)
        }
        state.mode == DashDazzService.MODE_DUAL && state.dualStyle == DashDazzService.DUAL_SPLIT_BEAT -> {
            Color(state.splitColorA) to Color(state.splitColorB)
        }
        else -> {
            Color(baseColor).let { it to it }
        }
    }

    CameraModule(
        ringA = halfA,
        ringB = halfB,
        modifier = modifier.semantics { contentDescription = "Live preview of the ring light" }
    )
}

private val BLEND_FRACTIONS = floatArrayOf(0f, 0.15f, 0.25f, 0.35f, 0.65f, 0.75f, 0.85f, 1f)

private fun blendedHalf(halfA: Color, halfB: Color, frac: Float): Color {
    val f = ((frac % 1f) + 1f) % 1f
    val mid = lerp(halfA, halfB, 0.5f)

    fun sideAt(i: Int): Color = when (i) {
        0, 1, 6, 7 -> halfA
        3, 4 -> halfB
        else -> mid
    }
    for (i in 0 until BLEND_FRACTIONS.size - 1) {
        val f0 = BLEND_FRACTIONS[i]
        val f1 = BLEND_FRACTIONS[i + 1]
        if (f in f0..f1) {
            val t = if (f1 > f0) (f - f0) / (f1 - f0) else 0f
            return lerp(sideAt(i), sideAt(i + 1), t)
        }
    }
    return halfA
}

@Composable
private fun SectionDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .height(1.dp)
            .clip(RoundedCornerShape(1.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant)
    )
}

@Composable
private fun CameraModule(ringA: Color, ringB: Color, modifier: Modifier = Modifier) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(28.dp))
                .background(MiuixTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 14.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {

                RingDot(halfA = ringA, halfB = ringB)
                RingDot(halfA = ringA, halfB = ringB)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                modifier = Modifier.height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.6f))
                )
            }
            Box(
                modifier = Modifier.height(44.dp),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Color.Gray.copy(alpha = 0.6f))
                )
            }
        }
    }
}

@Composable
private fun RingDot(
    halfA: Color,
    halfB: Color,
    size: androidx.compose.ui.unit.Dp = 44.dp
) {
    Canvas(
        Modifier
            .size(size)
            .semantics { contentDescription = "LED ring, left and right halves" }
    ) {
        val px = size.toPx()
        val r = px / 2f
        drawCircle(Color(0xFF141414), r)

        val w = (px * 0.16f).coerceAtLeast(4f)
        val leds = 48
        val span = 360f / leds
        val gap = 1.5f
        val inset = androidx.compose.ui.geometry.Offset(w / 2f, w / 2f)
        val arcSize = androidx.compose.ui.geometry.Size(px - w, px - w)
        for (i in 0 until leds) {
            val midAngle = i * span + span / 2f
            drawArc(
                color = blendedHalf(halfA, halfB, midAngle / 360f),
                startAngle = i * span + gap / 2f,
                sweepAngle = span - gap,
                useCenter = false,
                topLeft = inset,
                size = arcSize,
                style = Stroke(w)
            )
        }
        drawCircle(Color.Gray.copy(alpha = 0.5f), r - 1f, style = Stroke(1.dp.toPx()))
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DashDazzScreen(
    hasShizukuPermission: Boolean,
    isRootAvailable: Boolean,
    rootChecked: Boolean,
    hasConflict: Boolean,
    selectedDarkMode: Int,
    onDarkModeChange: (Int) -> Unit,
    onRequestShizukuPermission: () -> Unit,
    onRecheckRoot: () -> Unit = {}
) {
    val context = LocalContext.current
    val serviceState by DashDazzService.stateFlow.collectAsState()

    LaunchedEffect(isRootAvailable, rootChecked, serviceState.isRunning, serviceState.mode) {
        if (!rootChecked || isRootAvailable) return@LaunchedEffect
        if (!serviceState.isRunning) return@LaunchedEffect
        if (serviceState.mode == DashDazzService.MODE_DUAL) {
            DashDazzService.updateSettings(context, mode = DashDazzService.MODE_RAINBOW)
        }
    }

    LaunchedEffect(serviceState.audioReactive, serviceState.mode, serviceState.dualStyle) {
        if (!serviceState.audioReactive) return@LaunchedEffect
        if (serviceState.mode == DashDazzService.MODE_BREATHE) {
            DashDazzService.updateSettings(context, mode = DashDazzService.MODE_SOLID)
        }
        if (serviceState.mode == DashDazzService.MODE_DUAL &&
            serviceState.dualStyle !in DashDazzService.AUDIO_DUAL_STYLES
        ) {
            DashDazzService.updateSettings(context, dualStyle = DashDazzService.DUAL_BEAT_RAINBOW)
        }
    }

    var pendingAudioEnable by remember { mutableStateOf(false) }
    var pendingServiceStart by remember { mutableStateOf(false) }
    var startRequested by remember { mutableStateOf(false) }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted: Boolean ->
        val wantsStart = pendingServiceStart
        pendingServiceStart = false
        if (!granted) {
            pendingAudioEnable = false
            Toast.makeText(
                context,
                context.getString(R.string.audio_reactive_permission_denied),
                Toast.LENGTH_LONG
            ).show()
        } else {
            if (pendingAudioEnable) {
                pendingAudioEnable = false
                DashDazzService.updateSettings(context, audioReactive = true)
            }

            if (wantsStart) {
                startRequested = true
            } else {
                DashDazzService.relaunchAnimation(context)
            }
        }
    }

    fun selectMode(modeId: Int) {
        DashDazzService.updateSettings(context, mode = modeId)
    }

    var currentTab by remember { mutableIntStateOf(0) }
    var speedMs by remember { mutableLongStateOf(serviceState.speedMs) }
    var brightness by remember { mutableFloatStateOf(serviceState.brightness) }
    var duoHueOffset by remember { mutableIntStateOf(serviceState.duoHueOffset) }
    var notifDuration by remember { mutableIntStateOf(serviceState.notifActivateDuration) }
    var flashScreen by remember { mutableStateOf(Prefs.isFlashScreen(context)) }
    var autoCharging by remember { mutableStateOf(Prefs.isAutoCharging(context)) }
    val updateScope = rememberCoroutineScope()
    var updateStatusRaw by rememberSaveable {
        mutableStateOf(UpdateChecker.encode(UpdateChecker.UpdateStatus.Idle))
    }
    val updateStatus = remember(updateStatusRaw) { UpdateChecker.decode(updateStatusRaw) }
    fun setUpdateStatus(s: UpdateChecker.UpdateStatus) {
        updateStatusRaw = UpdateChecker.encode(s)
    }
    var updateDownloading by remember { mutableStateOf(false) }
    var updateProgress by remember { mutableIntStateOf(-1) }

    LaunchedEffect(Unit) {
        if (updateStatus !is UpdateChecker.UpdateStatus.Idle) return@LaunchedEffect
        if (!UpdateChecker.canAutoCheck(context)) return@LaunchedEffect
        setUpdateStatus(UpdateChecker.UpdateStatus.Checking)
        setUpdateStatus(UpdateChecker.check(context))
    }

    fun startUpdateCheck() {
        if (updateDownloading) return
        updateScope.launch {
            setUpdateStatus(UpdateChecker.UpdateStatus.Checking)
            setUpdateStatus(UpdateChecker.check(context))
        }
    }

    fun installUpdate(tag: String) {
        if (updateDownloading) return
        updateScope.launch {
            updateDownloading = true
            updateProgress = 0
            setUpdateStatus(UpdateChecker.UpdateStatus.Downloading(tag, 0))
            val result = UpdateChecker.download(context, tag) { pct ->
                updateProgress = pct
                setUpdateStatus(UpdateChecker.UpdateStatus.Downloading(tag, pct))
            }
            updateProgress = -1
            updateDownloading = false
            val apk = result.getOrNull()
            if (apk == null) {
                setUpdateStatus(UpdateChecker.UpdateStatus.Failed("download_failed"))
                return@launch
            }
            if (!UpdateChecker.canInstallPackages(context)) {
                Prefs.setPendingUpdateApk(context, apk.absolutePath)
                setUpdateStatus(UpdateChecker.UpdateStatus.NeedsInstallPermission(tag))
                runCatching {
                    context.startActivity(UpdateChecker.installPermissionIntent(context))
                }.onFailure {
                    Prefs.setPendingUpdateApk(context, null)
                    setUpdateStatus(UpdateChecker.UpdateStatus.Failed("no_installer"))
                }
                return@launch
            }
            val ok = runCatching {
                context.startActivity(UpdateChecker.installIntent(context, apk))
            }.isSuccess
            if (!ok) {
                setUpdateStatus(UpdateChecker.UpdateStatus.Failed("no_installer"))
            }
        }
    }

    fun onUpdateClick() {
        when (val s = updateStatus) {
            is UpdateChecker.UpdateStatus.Available -> installUpdate(s.tag)
            is UpdateChecker.UpdateStatus.NeedsInstallPermission -> {
                val path = Prefs.getPendingUpdateApk(context)
                val apk = path?.let { File(it) }
                if (apk != null && apk.exists() && UpdateChecker.canInstallPackages(context)) {
                    val ok = runCatching {
                        context.startActivity(UpdateChecker.installIntent(context, apk))
                    }.isSuccess
                    if (ok) Prefs.setPendingUpdateApk(context, null)
                    else setUpdateStatus(UpdateChecker.UpdateStatus.Failed("no_installer"))
                } else if (UpdateChecker.canInstallPackages(context)) {
                    installUpdate(s.tag)
                } else {
                    runCatching {
                        context.startActivity(UpdateChecker.installPermissionIntent(context))
                    }
                }
            }
            else -> startUpdateCheck()
        }
    }
    var showAudioTuningDialog by remember { mutableStateOf(false) }
    var showAppPicker by remember { mutableStateOf(false) }
    var allowedApps by remember { mutableStateOf(Prefs.getNotifApps(context)) }
    var appsFilterOn by remember { mutableStateOf(Prefs.isNotifAppsFilter(context)) }
    var showAudioAppPicker by remember { mutableStateOf(false) }
    var audioAllowedApps by remember { mutableStateOf(Prefs.getAudioApps(context)) }
    var audioAppsFilterOn by remember { mutableStateOf(Prefs.isAudioAppsFilter(context)) }

    var snack by remember { mutableStateOf<Triple<String, String?, (() -> Unit)?>?>(null) }
    LaunchedEffect(snack) {
        if (snack != null) {
            delay(3500)
            snack = null
        }
    }

    var degreesPerBeat by remember { mutableFloatStateOf(AudioReactiveEngine.degreesPerBeat) }
    LaunchedEffect(showAudioTuningDialog) {
        if (showAudioTuningDialog) degreesPerBeat = AudioReactiveEngine.degreesPerBeat
    }

    LaunchedEffect(serviceState.duoHueOffset) {
        duoHueOffset = serviceState.duoHueOffset
    }

    LaunchedEffect(serviceState.speedMs) { speedMs = serviceState.speedMs }
    LaunchedEffect(serviceState.brightness) { brightness = serviceState.brightness }
    LaunchedEffect(serviceState.notifActivateDuration) {
        notifDuration = serviceState.notifActivateDuration
    }

    val initialHsv = remember(serviceState.color) {
        FloatArray(3).also { AndroidColor.colorToHSV(serviceState.color, it) }
    }
    var hue by remember(serviceState.color) { mutableFloatStateOf(initialHsv[0]) }
    var saturation by remember(serviceState.color) { mutableFloatStateOf(initialHsv[1]) }
    var customPresets by remember { mutableStateOf(Prefs.getCustomPresets(context)) }
    var activeSplitSpot by remember { mutableIntStateOf(0) }
    var activeSpinSpot by remember { mutableIntStateOf(0) }

    fun getSelectedColor(): Int {
        val argb = AndroidColor.HSVToColor(floatArrayOf(hue, saturation, brightness))
        return if (argb == -1) 0xFFFEFEFE.toInt() else argb
    }

    fun withBrightness(argb: Int, v: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        val out = AndroidColor.HSVToColor(floatArrayOf(hsv[0], hsv[1], v.coerceIn(0.05f, 1f)))
        return if (out == -1) 0xFFFEFEFE.toInt() else out
    }

    fun withSaturation(argb: Int, sat: Float, v: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        val out = AndroidColor.HSVToColor(
            floatArrayOf(hsv[0], sat.coerceIn(0f, 1f), v.coerceIn(0.05f, 1f))
        )
        return if (out == -1) 0xFFFEFEFE.toInt() else out
    }

    fun withHue(argb: Int, h: Float, v: Float): Int {
        val hsv = FloatArray(3)
        AndroidColor.colorToHSV(argb, hsv)
        val hueNorm = ((h % 360f) + 360f) % 360f
        val out = AndroidColor.HSVToColor(
            floatArrayOf(hueNorm, hsv[1].coerceIn(0f, 1f), v.coerceIn(0.05f, 1f))
        )
        return if (out == -1) 0xFFFEFEFE.toInt() else out
    }

    fun startServiceWithCurrentSettings() {
            DashDazzService.start(
                context = context,
                mode = serviceState.mode,
                color = getSelectedColor(),
                speedMs = speedMs,
                brightness = brightness,
                screenMode = serviceState.screenMode,
                blinkOnNotification = serviceState.blinkOnNotification,
                palette = serviceState.palette,
                dualStyle = serviceState.dualStyle,
                splitColorA = serviceState.splitColorA,
                splitColorB = serviceState.splitColorB,
                spinColorA = serviceState.spinColorA,
                spinColorB = serviceState.spinColorB,
                duoHueOffset = duoHueOffset
            )
        }

    fun togglePower(checked: Boolean) {
        if (!hasShizukuPermission && !isRootAvailable) {
            onRequestShizukuPermission()
            return
        }
        if (checked) {

            if (serviceState.audioReactive) {
                val hasAudioPerm = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                if (!hasAudioPerm) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.audio_reactive_permission_rationale),
                        Toast.LENGTH_SHORT
                    ).show()
                    pendingAudioEnable = true
                    pendingServiceStart = true
                    audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    return
                }
            }
            startServiceWithCurrentSettings()
        } else {
            DashDazzService.stop(context)
        }
    }

    LaunchedEffect(startRequested) {
        if (!startRequested) return@LaunchedEffect
        startRequested = false
        startServiceWithCurrentSettings()
    }

    Scaffold(
        topBar = {
            TopAppBar(title = stringResource(R.string.app_name))
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MiuixTheme.colorScheme.surfaceVariant)
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                listOf(0 to R.string.tab_lighting, 1 to R.string.tab_settings).forEach { (tabIndex, titleRes) ->
                    val isSelected = (currentTab == tabIndex)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                if (isSelected) MiuixTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { currentTab = tabIndex }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(titleRes),
                            fontSize = 14.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            if (currentTab == 0) {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    var conflictDismissed by remember(hasConflict) {
                        mutableStateOf(!hasConflict || Prefs.isConflictDismissed(context))
                    }
                    if (hasConflict && !conflictDismissed) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.title_conflict_warning),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFFFF9800)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = stringResource(R.string.conflict_warning_desc),
                                    fontSize = 13.sp,
                                    color = Color.Gray
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                SuperArrow(
                                    title = stringResource(R.string.btn_open_settings),
                                    onClick = {
                                        openStripLightSettings(context)
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SuperArrow(
                                    title = stringResource(R.string.btn_ignore),
                                    onClick = {
                                        Prefs.setConflictDismissed(context, true)
                                        conflictDismissed = true
                                    }
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                LiveRingPreview(
                                    state = serviceState,
                                    baseColor = getSelectedColor(),
                                    brightness = brightness,
                                    modifier = Modifier.alpha(
                                        if (serviceState.isRunning && !serviceState.isScreenPaused) 1f else 0.35f
                                    )
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(end = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = if (serviceState.isRunning) {
                                            if (serviceState.isScreenPaused) {
                                                stringResource(R.string.notification_paused)
                                            } else {
                                                stringResource(R.string.status_service_running)
                                            }
                                        } else {
                                            stringResource(R.string.status_service_stopped)
                                        },
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.End,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Switch(
                                        checked = serviceState.isRunning,
                                        onCheckedChange = { togglePower(it) },
                                        modifier = Modifier
                                            .scale(1.3f)
                                            .padding(end = 4.dp)
                                    )
                                }
                            }
                            if (!hasShizukuPermission && !isRootAvailable) {
                                Spacer(modifier = Modifier.height(8.dp))
                                SuperArrow(
                                    title = stringResource(R.string.btn_grant_permission),
                                    summary = stringResource(R.string.shizuku_permission_needed),
                                    onClick = onRequestShizukuPermission
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.title_modes),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val modeItems = buildList {
                                    add(DashDazzService.MODE_RAINBOW to R.string.mode_rainbow)

                                    if (!serviceState.audioReactive) {
                                        add(DashDazzService.MODE_BREATHE to R.string.mode_breathe)
                                    }
                                    add(DashDazzService.MODE_SOLID to R.string.mode_solid)
                                    if (isRootAvailable) {
                                        add(DashDazzService.MODE_DUAL to R.string.mode_dual)
                                    }
                                }
                                modeItems.forEach { (modeId, titleRes) ->
                                    val isSelected = (serviceState.mode == modeId)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.surfaceVariant
                                            )
                                            .semantics {
                                                contentDescription = context.getString(
                                                    R.string.a11y_select,
                                                    context.getString(titleRes)
                                                )
                                            }
                                            .clickable {

                                                selectMode(modeId)
                                            }
                                            .padding(vertical = 10.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(titleRes),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (serviceState.mode == DashDazzService.MODE_RAINBOW && !serviceState.audioReactive) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.title_palettes),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Palettes.ALL.chunked(3).forEach { rowPalettes ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowPalettes.forEach { palette ->
                                                val isSelected = (serviceState.palette == palette.id)
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(10.dp))
                                                        .background(
                                                            if (isSelected) MiuixTheme.colorScheme.primary
                                                            else MiuixTheme.colorScheme.surfaceVariant
                                                        )
                                                        .clickable {
                                                            DashDazzService.updateSettings(context, palette = palette.id)
                                                        }
                                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = stringResource(palette.nameRes),
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                        color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                                        textAlign = TextAlign.Center,
                                                        maxLines = 1
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (isRootAvailable && serviceState.mode == DashDazzService.MODE_DUAL) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.title_dual_effects),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(12.dp))

                                val audioOn = serviceState.audioReactive
                                val dualEffects = buildList {
                                    if (audioOn) {
                                        add(Triple(DashDazzService.DUAL_BEAT_RAINBOW, R.string.dual_beat_rainbow, listOf(Color(0xFFFF1744), Color(0xFFFF9100), Color(0xFF2979FF))))
                                        add(Triple(DashDazzService.DUAL_SPLIT_BEAT, R.string.dual_split_beat, listOf(Color(serviceState.splitColorA), Color(serviceState.splitColorB))))
                                    } else {
                                        add(Triple(DashDazzService.DUAL_HARDWARE_SPLIT, R.string.dual_hardware_split, listOf(Color(serviceState.splitColorA), Color(serviceState.splitColorB))))
                                        add(Triple(DashDazzService.DUAL_SPIN, R.string.dual_spin, listOf(Color(serviceState.spinColorA), Color(serviceState.spinColorB))))
                                        add(Triple(DashDazzService.DUAL_POLICE, R.string.dual_police, listOf(Color(0xFFFF1744), Color(0xFF2979FF))))
                                        add(Triple(DashDazzService.DUAL_ALTERNATE, R.string.dual_alternate, listOf(Color(serviceState.spinColorA), Color(serviceState.spinColorB))))
                                        add(Triple(DashDazzService.DUAL_RAINBOW_DUO, R.string.dual_rainbow_duo, listOf(Color(0xFFFF1744), Color(0xFF00E676), Color(0xFF2979FF))))
                                    }
                                }

                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    dualEffects.chunked(2).forEach { rowEffects ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            rowEffects.forEach { (effectId, titleRes, gradientColors) ->
                                                val isSelected = (serviceState.dualStyle == effectId)
                                                Box(
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .clip(RoundedCornerShape(12.dp))
                                                        .background(
                                                            if (isSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.12f)
                                                            else MiuixTheme.colorScheme.surfaceVariant
                                                        )
                                                        .border(
                                                            width = if (isSelected) 1.5.dp else 0.dp,
                                                            color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                                            shape = RoundedCornerShape(12.dp)
                                                        )
                                                        .semantics {
                                                            contentDescription = context.getString(
                                                                R.string.a11y_select,
                                                                context.getString(titleRes)
                                                            )
                                                        }
                                                        .clickable {
                                                            DashDazzService.updateSettings(context, dualStyle = effectId)
                                                        }
                                                        .padding(horizontal = 10.dp, vertical = 12.dp)
                                                ) {
                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(24.dp)
                                                                .clip(CircleShape)
                                                                .background(
                                                                    androidx.compose.ui.graphics.Brush.linearGradient(gradientColors)
                                                                )
                                                                .border(1.dp, Color.Gray.copy(alpha = 0.4f), CircleShape)
                                                        )
                                                        Text(
                                                            text = stringResource(titleRes),
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                            color = if (isSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface,
                                                            maxLines = 1
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                if (serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.title_split_colors),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            0 to (R.string.spot_left to serviceState.splitColorA),
                                            1 to (R.string.spot_right to serviceState.splitColorB)
                                        ).forEach { (spotIndex, data) ->
                                            val (labelRes, spotColor) = data
                                            val isSpotSelected = (activeSplitSpot == spotIndex)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (isSpotSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                        else MiuixTheme.colorScheme.surfaceVariant
                                                    )
                                                    .border(
                                                        width = if (isSpotSelected) 1.5.dp else 0.dp,
                                                        color = if (isSpotSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .semantics {
                                                        contentDescription = context.getString(
                                                            R.string.a11y_select,
                                                            context.getString(labelRes)
                                                        )
                                                    }
                                                    .clickable {
                                                        activeSplitSpot = spotIndex
                                                        val hsv = FloatArray(3)
                                                        AndroidColor.colorToHSV(spotColor, hsv)
                                                        hue = hsv[0]
                                                        saturation = hsv[1]
                                                        brightness = hsv[2]
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(spotColor))
                                                            .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                                                    )
                                                    Text(
                                                        text = stringResource(labelRes),
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSpotSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSpotSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    val quickColors = listOf(
                                        Color(0xFFFF0000), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF4CAF50),
                                        Color(0xFF00BCD4), Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFFFFFFF)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        quickColors.forEach { qColor ->
                                            val currentColor = if (activeSplitSpot == 0) serviceState.splitColorA else serviceState.splitColorB
                                            val isSelected = (currentColor == qColor.toArgb())
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape)
                                                    .background(qColor)
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                                        shape = CircleShape
                                                    )
                                                    .semantics {
                                                        contentDescription = context.getString(R.string.a11y_pick_color)
                                                    }
                                                    .clickable {
                                                        val targetColor = qColor.toArgb()
                                                        val hsv = FloatArray(3)
                                                        AndroidColor.colorToHSV(targetColor, hsv)
                                                        hue = hsv[0]
                                                        saturation = hsv[1]
                                                        if (activeSplitSpot == 0) {
                                                            DashDazzService.updateSettings(context, splitColorA = targetColor)
                                                        } else {
                                                            DashDazzService.updateSettings(context, splitColorB = targetColor)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(if (qColor == Color.White) Color.Black else Color.White)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (serviceState.dualStyle == DashDazzService.DUAL_SPIN ||
                                    serviceState.dualStyle == DashDazzService.DUAL_ALTERNATE) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = stringResource(R.string.title_spin_colors),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(
                                            0 to (R.string.spin_step_1 to serviceState.spinColorA),
                                            1 to (R.string.spin_step_2 to serviceState.spinColorB)
                                        ).forEach { (spotIndex, data) ->
                                            val (labelRes, spotColor) = data
                                            val isSpotSelected = (activeSpinSpot == spotIndex)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(
                                                        if (isSpotSelected) MiuixTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                        else MiuixTheme.colorScheme.surfaceVariant
                                                    )
                                                    .border(
                                                        width = if (isSpotSelected) 1.5.dp else 0.dp,
                                                        color = if (isSpotSelected) MiuixTheme.colorScheme.primary else Color.Transparent,
                                                        shape = RoundedCornerShape(10.dp)
                                                    )
                                                    .semantics {
                                                        contentDescription = context.getString(
                                                            R.string.a11y_select,
                                                            context.getString(labelRes)
                                                        )
                                                    }
                                                    .clickable {
                                                        activeSpinSpot = spotIndex
                                                        val hsv = FloatArray(3)
                                                        AndroidColor.colorToHSV(spotColor, hsv)
                                                        hue = hsv[0]
                                                        saturation = hsv[1]
                                                        brightness = hsv[2]
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 10.dp)
                                            ) {
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(20.dp)
                                                            .clip(CircleShape)
                                                            .background(Color(spotColor))
                                                            .border(1.dp, Color.Gray.copy(alpha = 0.5f), CircleShape)
                                                    )
                                                    Text(
                                                        text = stringResource(labelRes),
                                                        fontSize = 12.sp,
                                                        fontWeight = if (isSpotSelected) FontWeight.Bold else FontWeight.Medium,
                                                        color = if (isSpotSelected) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurface
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    val quickColors = listOf(
                                        Color(0xFFFF0000), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF4CAF50),
                                        Color(0xFF00BCD4), Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFFFFFFF)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        quickColors.forEach { qColor ->
                                            val currentColor = if (activeSpinSpot == 0) serviceState.spinColorA else serviceState.spinColorB
                                            val isSelected = (currentColor == qColor.toArgb())
                                            Box(
                                                modifier = Modifier
                                                    .size(30.dp)
                                                    .clip(CircleShape)
                                                    .background(qColor)
                                                    .border(
                                                        width = if (isSelected) 2.dp else 1.dp,
                                                        color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.4f),
                                                        shape = CircleShape
                                                    )
                                                    .semantics {
                                                        contentDescription = context.getString(R.string.a11y_pick_color)
                                                    }
                                                    .clickable {
                                                        val targetColor = qColor.toArgb()
                                                        val hsv = FloatArray(3)
                                                        AndroidColor.colorToHSV(targetColor, hsv)
                                                        hue = hsv[0]
                                                        saturation = hsv[1]
                                                        if (activeSpinSpot == 0) {
                                                            DashDazzService.updateSettings(context, spinColorA = targetColor)
                                                        } else {
                                                            DashDazzService.updateSettings(context, spinColorB = targetColor)
                                                        }
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(5.dp)
                                                            .clip(CircleShape)
                                                            .background(if (qColor == Color.White) Color.Black else Color.White)
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }

                                if (serviceState.dualStyle == DashDazzService.DUAL_RAINBOW_DUO) {
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        text = "Hue Offset: ${duoHueOffset}°",
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Slider(
                                            progress = duoHueOffset.toFloat(),
                                            onProgressChange = { value ->
                                                duoHueOffset = value.toInt()
                                                DashDazzService.updateSettings(context, duoHueOffset = duoHueOffset)
                                            },
                                            minValue = 0f,
                                            maxValue = 360f,
                                            modifier = Modifier.weight(1f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        val anglePresets = listOf(
                                            60 to "60°",
                                            90 to "90°",
                                            120 to "120°",
                                            180 to "180°"
                                        )
                                        anglePresets.forEach { (angle, label) ->
                                            val isPresetSelected = (duoHueOffset == angle)
                                            Box(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(
                                                        if (isPresetSelected) MiuixTheme.colorScheme.primary
                                                        else MiuixTheme.colorScheme.surfaceVariant
                                                    )
                                                    .clickable(
                                                        onClickLabel = context.getString(
                                                            R.string.a11y_select, label
                                                        )
                                                    ) {
                                                        duoHueOffset = angle
                                                        DashDazzService.updateSettings(context, duoHueOffset = angle)
                                                    }
                                                    .padding(vertical = 8.dp),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = label,
                                                    fontSize = 12.sp,
                                                    fontWeight = if (isPresetSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isPresetSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                                    textAlign = TextAlign.Center
                                                )
                                            }
                                        }
                                    }
                                }

                                if (serviceState.dualStyle == DashDazzService.DUAL_BEAT_RAINBOW) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = stringResource(R.string.title_beat_rainbow),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = stringResource(R.string.label_hue, duoHueOffset),
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Slider(
                                        progress = duoHueOffset.toFloat(),
                                        onProgressChange = {
                                            val v = it.toInt().coerceIn(0, 360)
                                            duoHueOffset = v
                                            DashDazzService.updateSettings(context, duoHueOffset = v)
                                        },
                                        minValue = 0f,
                                        maxValue = 360f,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(8.dp))

                                    Text(
                                        text = stringResource(
                                            R.string.label_degrees_per_beat,
                                            degreesPerBeat.toInt()
                                        ),
                                        fontSize = 13.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Slider(
                                        progress = degreesPerBeat,
                                        onProgressChange = {
                                            degreesPerBeat = it
                                            AudioReactiveEngine.setDegreesPerBeat(context, it)
                                        },
                                        minValue = AudioReactiveEngine.MIN_DEGREES_PER_BEAT,
                                        maxValue = AudioReactiveEngine.MAX_DEGREES_PER_BEAT,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.beat_rainbow_hint),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }

                                if (serviceState.dualStyle == DashDazzService.DUAL_SPLIT_BEAT) {
                                    Text(
                                        text = stringResource(R.string.title_split_beat_colors),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = stringResource(R.string.dual_split_beat_hint),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))

                                    val beatColors = listOf(serviceState.splitColorA, serviceState.splitColorB)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        beatColors.forEachIndexed { idx, argb ->
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(28.dp)
                                                        .clip(CircleShape)
                                                        .background(Color(argb))
                                                        .border(
                                                            1.dp,
                                                            Color.Gray.copy(alpha = 0.5f),
                                                            CircleShape
                                                        )
                                                )
                                                Spacer(modifier = Modifier.height(2.dp))
                                                Text(
                                                    text = stringResource(
                                                        if (idx == 0) R.string.spot_left else R.string.spot_right
                                                    ),
                                                    fontSize = 9.sp
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.title_adjustments),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(16.dp))

                                val breatheSilicon = serviceState.mode == DashDazzService.MODE_BREATHE && isRootAvailable
                                val isSpeedGone = serviceState.mode == DashDazzService.MODE_SOLID ||
                                        (serviceState.mode == DashDazzService.MODE_DUAL && (!isRootAvailable || serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT)) ||
                                        breatheSilicon
                                if (breatheSilicon) {
                                    Text(
                                        text = stringResource(R.string.breathe_silicon_note),
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                }
                                if (!isSpeedGone) {

                                    Text(
                                        text = stringResource(R.string.label_speed, speedMs),
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Slider(
                                        progress = speedMs.toFloat(),
                                        onProgressChange = { value ->
                                            speedMs = value.toLong()
                                            DashDazzService.updateSettings(context, speedMs = speedMs)
                                        },
                                        minValue = 10f,
                                        maxValue = 500f,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }

                                Spacer(modifier = Modifier.height(12.dp))

                                Text(
                                    text = stringResource(R.string.label_brightness, (brightness * 100).toInt()),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Slider(
                                    progress = brightness,
                                    onProgressChange = { value ->
                                        brightness = value
                                        if (serviceState.mode == DashDazzService.MODE_DUAL) {
                                            if (serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) {

                                                val base = if (activeSplitSpot == 0) serviceState.splitColorA else serviceState.splitColorB
                                                val spot = withBrightness(base, brightness)
                                                if (activeSplitSpot == 0) {
                                                    DashDazzService.updateSettings(context, brightness = brightness, splitColorA = spot)
                                                } else {
                                                    DashDazzService.updateSettings(context, brightness = brightness, splitColorB = spot)
                                                }
                                            } else if (serviceState.dualStyle == DashDazzService.DUAL_SPIN) {
                                                val base = if (activeSpinSpot == 0) serviceState.spinColorA else serviceState.spinColorB
                                                val spot = withBrightness(base, brightness)
                                                if (activeSpinSpot == 0) {
                                                    DashDazzService.updateSettings(context, brightness = brightness, spinColorA = spot)
                                                } else {
                                                    DashDazzService.updateSettings(context, brightness = brightness, spinColorB = spot)
                                                }
                                            } else {
                                                DashDazzService.updateSettings(context, brightness = brightness)
                                            }
                                        } else {
                                            val newColor = getSelectedColor()
                                            DashDazzService.updateSettings(
                                                context,
                                                brightness = brightness,
                                                color = newColor
                                            )
                                        }
                                    },
                                    minValue = 0.05f,
                                    maxValue = 1f,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                val isColorGone = serviceState.mode == DashDazzService.MODE_RAINBOW ||
                                        serviceState.chargingIndicator ||
                                        (serviceState.mode == DashDazzService.MODE_DUAL &&
                                        (!isRootAvailable ||
                                        (serviceState.dualStyle != DashDazzService.DUAL_HARDWARE_SPLIT &&
                                        serviceState.dualStyle != DashDazzService.DUAL_SPIN)))
                                if (!isColorGone) {

                                    Text(
                                        text = stringResource(R.string.label_saturation, (saturation * 100).toInt()),
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Slider(
                                        progress = saturation,
                                        onProgressChange = { value ->
                                            saturation = value
                                            if (serviceState.mode == DashDazzService.MODE_DUAL) {
                                                if (serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) {

                                                    val base = if (activeSplitSpot == 0) serviceState.splitColorA else serviceState.splitColorB
                                                    val spot = withSaturation(base, saturation, brightness)
                                                    if (activeSplitSpot == 0) {
                                                        DashDazzService.updateSettings(context, splitColorA = spot)
                                                    } else {
                                                        DashDazzService.updateSettings(context, splitColorB = spot)
                                                    }
                                                } else if (serviceState.dualStyle == DashDazzService.DUAL_SPIN) {
                                                    val base = if (activeSpinSpot == 0) serviceState.spinColorA else serviceState.spinColorB
                                                    val spot = withSaturation(base, saturation, brightness)
                                                    if (activeSpinSpot == 0) {
                                                        DashDazzService.updateSettings(context, spinColorA = spot)
                                                    } else {
                                                        DashDazzService.updateSettings(context, spinColorB = spot)
                                                    }
                                                }
                                            } else {
                                                val newColor = getSelectedColor()
                                                DashDazzService.updateSettings(context, color = newColor)
                                            }
                                        },
                                        minValue = 0.05f,
                                        maxValue = 1f,
                                        modifier = Modifier.fillMaxWidth()
                                    )

                                    Spacer(modifier = Modifier.height(12.dp))

                                    Text(
                                        text = stringResource(R.string.label_hue, hue.toInt()),
                                        fontSize = 14.sp
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        Slider(
                                            progress = hue,
                                            onProgressChange = { value ->
                                                hue = value
                                                if (saturation < 0.1f) saturation = 1f
                                                if (serviceState.mode == DashDazzService.MODE_DUAL) {
                                                    if (serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) {

                                                        val base = if (activeSplitSpot == 0) serviceState.splitColorA else serviceState.splitColorB
                                                        val spot = withHue(base, hue, brightness)
                                                        if (activeSplitSpot == 0) {
                                                            DashDazzService.updateSettings(context, splitColorA = spot)
                                                        } else {
                                                            DashDazzService.updateSettings(context, splitColorB = spot)
                                                        }
                                                    } else if (serviceState.dualStyle == DashDazzService.DUAL_SPIN) {
                                                        val base = if (activeSpinSpot == 0) serviceState.spinColorA else serviceState.spinColorB
                                                        val spot = withHue(base, hue, brightness)
                                                        if (activeSpinSpot == 0) {
                                                            DashDazzService.updateSettings(context, spinColorA = spot)
                                                        } else {
                                                            DashDazzService.updateSettings(context, spinColorB = spot)
                                                        }
                                                    }
                                                } else {
                                                    val newColor = getSelectedColor()
                                                    DashDazzService.updateSettings(context, color = newColor)
                                                }
                                            },
                                            minValue = 0f,
                                            maxValue = 360f,
                                            modifier = Modifier.weight(1f)
                                        )
                                        val previewColor = if (serviceState.mode == DashDazzService.MODE_DUAL) {
                                            if (serviceState.dualStyle == DashDazzService.DUAL_HARDWARE_SPLIT) {
                                                if (activeSplitSpot == 0) serviceState.splitColorA else serviceState.splitColorB
                                            } else if (serviceState.dualStyle == DashDazzService.DUAL_SPIN) {
                                                if (activeSpinSpot == 0) serviceState.spinColorA else serviceState.spinColorB
                                            } else getSelectedColor()
                                        } else getSelectedColor()

                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .clip(CircleShape)
                                                .background(Color(previewColor))
                                                .border(1.5.dp, Color.Gray, CircleShape)
                                        )
                                    }
                                }
                            }
                        }

                    if (serviceState.mode != DashDazzService.MODE_DUAL &&
                        serviceState.mode != DashDazzService.MODE_RAINBOW &&
                        !serviceState.chargingIndicator) {
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.title_presets),
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )

                                Spacer(modifier = Modifier.height(12.dp))

                                val standardPresets = listOf(
                                    Triple(0f, 1f, Color(0xFFFF0000)),
                                    Triple(30f, 1f, Color(0xFFFF9800)),
                                    Triple(60f, 1f, Color(0xFFFFEB3B)),
                                    Triple(120f, 1f, Color(0xFF4CAF50)),
                                    Triple(180f, 1f, Color(0xFF00BCD4)),
                                    Triple(240f, 1f, Color(0xFF2196F3)),
                                    Triple(280f, 1f, Color(0xFF9C27B0)),
                                    Triple(316f, 0.50f, Color(0xFFFFFFFF))
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    standardPresets.forEach { (presetHue, presetSat, color) ->
                                        val isSelected = hueDistance(hue, presetHue) < 2f && Math.abs(saturation - presetSat) < 0.05f
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(color)
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 1.dp,
                                                    color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    val targetColor = AndroidColor.HSVToColor(floatArrayOf(presetHue, presetSat, brightness)).let {
                                                        if (it == -1) 0xFFFEFEFE.toInt() else it
                                                    }
                                                    hue = presetHue
                                                    saturation = presetSat
                                                    DashDazzService.updateSettings(context, color = targetColor)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (presetSat < 0.6f && color == Color.White) Color.Black else Color.White)
                                                )
                                            }
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(16.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = stringResource(R.string.title_saved_presets),
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )

                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MiuixTheme.colorScheme.primary.copy(alpha = 0.15f))
                                            .clickable {
                                                Prefs.saveCustomPreset(context, hue, saturation)
                                                customPresets = Prefs.getCustomPresets(context)
                                                snack = Triple(context.getString(R.string.preset_saved), null, null)
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.btn_save_preset),
                                            color = MiuixTheme.colorScheme.primary,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))

                                if (customPresets.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.hint_no_saved_presets),
                                        fontSize = 12.sp,
                                        color = Color.Gray
                                    )
                                } else {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        customPresets.forEachIndexed { index, (presetHue, presetSat) ->
                                            val isSelected = hueDistance(hue, presetHue) < 2f && Math.abs(saturation - presetSat) < 0.05f
                                            val presetColorInt = AndroidColor.HSVToColor(floatArrayOf(presetHue, presetSat, 1.0f))
                                            val presetColor = Color(presetColorInt)
                                            Box(
                                                modifier = Modifier
                                                    .size(34.dp)
                                                    .clip(CircleShape)
                                                    .background(presetColor)
                                                .border(
                                                    width = if (isSelected) 2.5.dp else 1.dp,
                                                    color = if (isSelected) MiuixTheme.colorScheme.primary else Color.Gray.copy(alpha = 0.5f),
                                                    shape = CircleShape
                                                )
                                                .combinedClickable(
                                                    onClickLabel = context.getString(R.string.a11y_apply_preset),
                                                    onLongClickLabel = context.getString(R.string.a11y_delete_preset),
                                                    onClick = {
                                                        val targetColor = AndroidColor.HSVToColor(floatArrayOf(presetHue, presetSat, brightness)).let {
                                                            if (it == -1) 0xFFFEFEFE.toInt() else it
                                                        }
                                                        hue = presetHue
                                                        saturation = presetSat
                                                        DashDazzService.updateSettings(context, color = targetColor)
                                                    },
                                                    onLongClick = {
                                                        val removed = presetHue to presetSat
                                                        Prefs.removeCustomPreset(context, index)
                                                        customPresets = Prefs.getCustomPresets(context)
                                                        snack = Triple(
                                                            context.getString(R.string.preset_deleted),
                                                            context.getString(R.string.snackbar_undo)
                                                        ) {
                                                            Prefs.saveCustomPreset(context, removed.first, removed.second)
                                                            customPresets = Prefs.getCustomPresets(context)
                                                        }
                                                    }
                                                ),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                if (isSelected) {
                                                    Box(
                                                        modifier = Modifier
                                                            .size(6.dp)
                                                            .clip(CircleShape)
                                                            .background(if (presetSat < 0.4f) Color.Black else Color.White)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(6.dp))

                                    Text(
                                        text = stringResource(R.string.hint_long_press_delete),
                                        fontSize = 11.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }
                    snack?.let { (message, actionLabel, onAction) ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(MiuixTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = message,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                if (actionLabel != null && onAction != null) {
                                    Text(
                                        text = actionLabel,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.primary,
                                        modifier = Modifier.clickable {
                                            onAction()
                                            snack = null
                                        }
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            } else {

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 4.dp)
                        .animateContentSize(),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.title_dark_mode),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                val themeOptions = listOf(
                                    0 to R.string.theme_system,
                                    1 to R.string.theme_light,
                                    2 to R.string.theme_dark
                                )
                                themeOptions.forEach { (modeVal, titleRes) ->
                                    val isSelected = (selectedDarkMode == modeVal)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.surfaceVariant
                                            )
                                            .semantics {
                                                contentDescription = context.getString(
                                                    R.string.a11y_select,
                                                    context.getString(titleRes)
                                                )
                                            }
                                            .clickable {
                                                onDarkModeChange(modeVal)
                                            }
                                            .padding(vertical = 12.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(titleRes),
                                            fontSize = 14.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = stringResource(R.string.audio_card_title),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            Text(
                                text = stringResource(R.string.audio_card_desc),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )

                            Spacer(modifier = Modifier.height(10.dp))
                            SuperSwitch(
                                title = stringResource(R.string.audio_reactive_toggle),
                                summary = stringResource(
                                    if (serviceState.audioReactive) {
                                        R.string.audio_reactive_on_desc
                                    } else {
                                        R.string.audio_reactive_off_desc
                                    }
                                ),
                                checked = serviceState.audioReactive,
                                onCheckedChange = { enabled ->

                                    if (enabled) {
                                        val hasAudioPerm = ContextCompat.checkSelfPermission(
                                            context, Manifest.permission.RECORD_AUDIO
                                        ) == PackageManager.PERMISSION_GRANTED
                                        if (!hasAudioPerm) {
                                            pendingAudioEnable = true
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.audio_reactive_permission_rationale),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                            return@SuperSwitch
                                        }
                                    } else {
                                        pendingAudioEnable = false
                                    }
                                    DashDazzService.updateSettings(context, audioReactive = enabled)
                                }
                            )

                            if (serviceState.audioReactive) {
                                Spacer(modifier = Modifier.height(10.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(MiuixTheme.colorScheme.primary)
                                        .clickable { showAudioTuningDialog = true }
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.audio_open_tuning),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SuperArrow(
                                title = stringResource(R.string.audio_apps_title),
                                summary = if (!audioAppsFilterOn) {
                                    stringResource(R.string.audio_apps_all)
                                } else {
                                    stringResource(R.string.audio_apps_count, audioAllowedApps.size)
                                },
                                onClick = {
                                    audioAllowedApps = Prefs.getAudioApps(context)
                                    audioAppsFilterOn = Prefs.isAudioAppsFilter(context)
                                    showAudioAppPicker = true
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = stringResource(R.string.title_features),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )

                            Text(
                                text = stringResource(R.string.feature_screen_mode_desc),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    DashDazzService.SCREEN_ON_ONLY to R.string.screen_mode_on,
                                    DashDazzService.SCREEN_OFF_ONLY to R.string.screen_mode_off,
                                    DashDazzService.SCREEN_ALWAYS to R.string.screen_mode_always,
                                    DashDazzService.SCREEN_NOTIF_ONLY to R.string.screen_mode_notif_only
                                ).forEach { (modeVal, titleRes) ->
                                    val isSelected = (serviceState.screenMode == modeVal)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.surfaceVariant
                                            )
                                            .semantics {
                                                contentDescription = context.getString(
                                                    R.string.a11y_select,
                                                    context.getString(titleRes)
                                                )
                                            }
                                            .clickable {
                                                DashDazzService.updateSettings(context, screenMode = modeVal)
                                            }
                                            .padding(vertical = 12.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(titleRes),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            SuperSwitch(
                                title = stringResource(R.string.flash_screen_title),
                                summary = stringResource(R.string.flash_screen_desc),
                                checked = flashScreen,
                                onCheckedChange = {
                                    flashScreen = it
                                    Prefs.setFlashScreen(context, it)
                                }
                            )
                            SuperSwitch(
                                title = stringResource(R.string.auto_charging_title),
                                summary = stringResource(R.string.auto_charging_desc),
                                checked = autoCharging,
                                onCheckedChange = {
                                    autoCharging = it
                                    DashDazzService.setAutoCharging(context, it)
                                }
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.title_frame_rate),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf(
                                    Prefs.FRAME_SMOOTH_MS to R.string.frame_rate_smooth,
                                    Prefs.FRAME_BALANCED_MS to R.string.frame_rate_balanced,
                                    Prefs.FRAME_ECO_MS to R.string.frame_rate_eco
                                ).forEach { (ms, titleRes) ->
                                    val isSelected = (serviceState.frameMinMs == ms)
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(
                                                if (isSelected) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.surfaceVariant
                                            )
                                            .semantics {
                                                contentDescription = context.getString(
                                                    R.string.a11y_select,
                                                    context.getString(titleRes)
                                                )
                                            }
                                            .clickable {
                                                DashDazzService.updateSettings(context, frameMinMs = ms)
                                            }
                                            .padding(vertical = 10.dp, horizontal = 4.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(titleRes),
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                            textAlign = TextAlign.Center,
                                            maxLines = 1
                                        )
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(R.string.frame_rate_desc),
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            if (serviceState.screenMode == DashDazzService.SCREEN_NOTIF_ONLY){

                                Text(
                                    text = stringResource(R.string.title_notification_mode),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(
                                        Prefs.NOTIF_MODE_OFF to R.string.notif_mode_off,
                                        Prefs.NOTIF_MODE_ACTIVATE to R.string.notif_mode_activate
                                    ).forEach { (modeVal, titleRes) ->
                                        val isSelected = (serviceState.notifMode == modeVal)
                                        Box(
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(
                                                    if (isSelected) MiuixTheme.colorScheme.primary
                                                    else MiuixTheme.colorScheme.surfaceVariant
                                                )
                                                .semantics {
                                                    contentDescription = context.getString(
                                                        R.string.a11y_select,
                                                        context.getString(titleRes)
                                                    )
                                                }
                                                .clickable {
                                                    if (modeVal == Prefs.NOTIF_MODE_ACTIVATE &&
                                                        !DashDazzNotificationListener.isNotificationAccessGranted(context)
                                                    ) {
                                                        DashDazzNotificationListener.openNotificationAccessSettings(context)
                                                        Prefs.setPendingNotifMode(context, modeVal)
                                                        Toast.makeText(context, R.string.btn_grant_notif_access, Toast.LENGTH_LONG).show()

                                                        return@clickable
                                                    }
                                                    DashDazzService.updateSettings(context, notifMode = modeVal)
                                                }
                                                .padding(vertical = 10.dp, horizontal = 4.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = stringResource(titleRes),
                                                fontSize = 12.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) Color.White else MiuixTheme.colorScheme.onSurface,
                                                textAlign = TextAlign.Center,
                                                maxLines = 1
                                            )
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = stringResource(
                                        when (serviceState.notifMode) {
                                            Prefs.NOTIF_MODE_ACTIVATE -> R.string.notif_mode_activate_desc
                                            else -> R.string.notif_mode_off_desc
                                        }
                                    ),
                                    fontSize = 12.sp,
                                    color = Color.Gray,
                                    modifier = Modifier.padding(horizontal = 16.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                SuperArrow(
                                    title = stringResource(R.string.notif_apps_title),
                                    summary = if (!appsFilterOn) {
                                        stringResource(R.string.notif_apps_all)
                                    } else {
                                        stringResource(R.string.notif_apps_count, allowedApps.size)
                                    },
                                    onClick = {
                                        allowedApps = Prefs.getNotifApps(context)
                                        appsFilterOn = Prefs.isNotifAppsFilter(context)
                                        showAppPicker = true
                                    }
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (serviceState.notifMode == Prefs.NOTIF_MODE_ACTIVATE) {
                                    Spacer(modifier = Modifier.height(12.dp))
                                    Text(
                                        text = stringResource(R.string.title_notif_duration, notifDuration),
                                        fontSize = 14.sp,
                                        modifier = Modifier.padding(horizontal = 16.dp)
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Slider(
                                        progress = notifDuration.toFloat(),
                                        onProgressChange = { value ->
                                            notifDuration = value.toInt().coerceIn(1, 60)
                                            DashDazzService.updateSettings(
                                                context,
                                                notifActivateDuration = notifDuration
                                            )
                                        },
                                        minValue = 1f,
                                        maxValue = 60f,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
                                SuperArrow(
                                    title = stringResource(R.string.notif_reconnect),
                                    summary = stringResource(R.string.notif_reconnect_desc),
                                    onClick = {
                                        try {
                                            android.service.notification.NotificationListenerService.requestRebind(
                                                android.content.ComponentName(
                                                    context,
                                                    DashDazzNotificationListener::class.java
                                                )
                                            )
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.notif_reconnect_done),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                        } catch (_: Exception) {
                                        }
                                    }
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = stringResource(R.string.title_root),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            SuperArrow(
                                title = stringResource(
                                    when {
                                        isRootAvailable -> R.string.root_granted
                                        !rootChecked -> R.string.root_checking
                                        else -> R.string.root_not_available
                                    }
                                ),
                                summary = stringResource(
                                    when {
                                        isRootAvailable -> R.string.root_granted_desc
                                        !rootChecked -> R.string.root_checking_desc
                                        else -> R.string.root_not_available_desc
                                    }
                                ),
                                onClick = onRecheckRoot
                            )
                            if (isRootAvailable) {
                                SuperArrow(
                                    title = stringResource(R.string.survival_title),
                                    summary = stringResource(R.string.survival_desc),
                                    onClick = {
                                        Thread {
                                            val ok = runCatching {
                                                LightsBridge.ensureBackgroundSurvival(context.packageName)
                                            }.getOrDefault(false)
                                            android.os.Handler(android.os.Looper.getMainLooper()).post {
                                                Toast.makeText(
                                                    context,
                                                    context.getString(
                                                        if (ok) R.string.survival_ok
                                                        else R.string.survival_failed
                                                    ),
                                                    Toast.LENGTH_SHORT
                                                ).show()
                                            }
                                        }.start()
                                    }
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = stringResource(R.string.title_shizuku),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                            if (hasShizukuPermission) {
                                SuperArrow(
                                    title = stringResource(R.string.shizuku_connected),
                                    summary = "Binder bridge active (uid 2000)",
                                    onClick = {}
                                )
                            } else {
                                SuperArrow(
                                    title = stringResource(R.string.btn_grant_permission),
                                    summary = stringResource(R.string.shizuku_permission_needed),
                                    onClick = onRequestShizukuPermission
                                )
                            }
                        }
                    }

                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = stringResource(R.string.title_about),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = stringResource(
                                    R.string.about_version,
                                    BuildConfig.VERSION_NAME,
                                    BuildConfig.VERSION_CODE
                                ),
                                fontSize = 13.sp,
                                color = MiuixTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = stringResource(R.string.about_desc),
                                fontSize = 13.sp,
                                color = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(10.dp))
                            SuperArrow(
                                title = stringResource(R.string.update_check_title),
                                summary = updateStatusLabel(
                                    updateStatus,
                                    updateProgress,
                                    stringResource(R.string.update_check_desc),
                                    stringResource(R.string.update_checking_short),
                                    stringResource(R.string.update_install),
                                    stringResource(R.string.update_up_to_date),
                                    stringResource(R.string.update_failed),
                                    stringResource(R.string.update_download_failed),
                                    stringResource(R.string.update_rate_limited),
                                    stringResource(R.string.update_asset_pending),
                                    stringResource(R.string.update_need_permission),
                                    stringResource(R.string.update_no_installer)
                                ),
                                onClick = { onUpdateClick() }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }

            if (showAudioTuningDialog) {
                AudioTuningDialog(
                    onDismiss = { showAudioTuningDialog = false },
                    onResetDefaults = { degreesPerBeat = AudioReactiveEngine.DEFAULT_DEGREES_PER_BEAT }
                )
            }
            if (showAppPicker) {
                NotifAppPickerDialog(
                    allowed = allowedApps,
                    filterOn = appsFilterOn,
                    onFilterChange = {
                        appsFilterOn = it
                        Prefs.setNotifAppsFilter(context, it)
                    },
                    onChange = {
                        allowedApps = it
                        Prefs.setNotifApps(context, it)
                    },
                    onDismiss = { showAppPicker = false }
                )
            }
            if (showAudioAppPicker) {
                NotifAppPickerDialog(
                    allowed = audioAllowedApps,
                    filterOn = audioAppsFilterOn,
                    onFilterChange = {
                        audioAppsFilterOn = it
                        Prefs.setAudioAppsFilter(context, it)
                        AudioReactiveEngine.refreshAudioGate()
                    },
                    onChange = {
                        audioAllowedApps = it
                        Prefs.setAudioApps(context, it)
                        AudioReactiveEngine.refreshAudioGate()
                    },
                    onDismiss = { showAudioAppPicker = false },
                    titleRes = R.string.audio_apps_title,
                    allTitleRes = R.string.audio_apps_all,
                    allDescRes = R.string.audio_apps_all_desc,
                    showCustomizer = false,
                    micNoteRes = R.string.audio_apps_mic_note
                )
            }
        }
    }
}

@Composable
fun NotifAppPickerDialog(
    allowed: Set<String>,
    filterOn: Boolean,
    onFilterChange: (Boolean) -> Unit,
    onChange: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    titleRes: Int = R.string.notif_apps_title,
    allTitleRes: Int = R.string.notif_apps_all,
    allDescRes: Int = R.string.notif_apps_all_desc,
    showCustomizer: Boolean = true,
    micNoteRes: Int? = null
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val apps = remember {
        runCatching {
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName }
                .filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .sortedBy {
                    runCatching { pm.getApplicationLabel(it).toString().lowercase() }
                        .getOrDefault(it.packageName)
                }
        }.getOrDefault(emptyList())
    }
    val labels = remember(apps) {
        apps.associate { info ->
            info.packageName to runCatching { pm.getApplicationLabel(info).toString() }
                .getOrDefault(info.packageName)
        }
    }
    val effective = if (!filterOn) apps.map { it.packageName }.toSet() else allowed
    var expandedPkg by remember { mutableStateOf<String?>(null) }
    var cfgTick by remember { mutableIntStateOf(0) }
    val quickColors = listOf(
        Color(0xFFFF0000), Color(0xFFFF9800), Color(0xFFFFEB3B), Color(0xFF4CAF50),
        Color(0xFF00BCD4), Color(0xFF2196F3), Color(0xFF9C27B0), Color(0xFFFFFFFF)
    )
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(titleRes),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )
                if (micNoteRes != null && AudioReactiveEngine.audioSource == AudioReactiveEngine.SOURCE_MIC) {
                    Text(
                        text = stringResource(micNoteRes),
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                SuperSwitch(
                    title = stringResource(allTitleRes),
                    summary = stringResource(allDescRes),
                    checked = !filterOn,
                    onCheckedChange = { on ->
                        if (on) {
                            onFilterChange(false)
                        } else {
                            onFilterChange(true)
                            if (allowed.isEmpty()) {
                                onChange(apps.map { it.packageName }.toSet())
                            }
                        }
                    }
                )
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(apps, key = { it.packageName }) { info ->
                        val pkg = info.packageName
                        val on = pkg in effective
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    if (!filterOn) {
                                        onFilterChange(true)
                                        onChange(apps.map { it.packageName }.toSet() - pkg)
                                    } else {
                                        onChange(if (on) effective - pkg else effective + pkg)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            val icon = remember(pkg) {
                                runCatching {
                                    pm.getApplicationIcon(pkg).toBitmap().asImageBitmap()
                                }.getOrNull()
                            }
                            if (icon != null) {
                                Image(
                                    bitmap = icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(32.dp)
                                )
                            } else {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .clip(CircleShape)
                                        .background(MiuixTheme.colorScheme.surfaceVariant)
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = labels[pkg] ?: pkg,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Text(
                                    text = pkg,
                                    fontSize = 10.sp,
                                    color = Color.Gray,
                                    maxLines = 1
                                )
                            }
                            if (on && showCustomizer) {
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (expandedPkg == pkg) MiuixTheme.colorScheme.primary
                                            else MiuixTheme.colorScheme.surfaceVariant
                                        )
                                        .clickable { expandedPkg = if (expandedPkg == pkg) null else pkg }
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stringResource(R.string.notif_app_custom),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (expandedPkg == pkg) Color.White
                                        else MiuixTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Box(
                                Modifier
                                    .size(20.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (on) MiuixTheme.colorScheme.primary
                                        else Color.Gray.copy(alpha = 0.35f)
                                    )
                            )
                        }
                        if (showCustomizer && expandedPkg == pkg && on) {
                            cfgTick.let {}
                            val cfg = Prefs.getAppNotifConfig(context, pkg)
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 44.dp, end = 4.dp, bottom = 6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (cfg.color == null) MiuixTheme.colorScheme.primary
                                                else MiuixTheme.colorScheme.surfaceVariant
                                            )
                                            .clickable {
                                                Prefs.setAppNotifConfig(context, pkg, null, cfg.durationSec)
                                                cfgTick++
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.notif_app_default),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (cfg.color == null) Color.White
                                            else MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                    quickColors.forEach { qColor ->
                                        Box(
                                            modifier = Modifier
                                                .size(26.dp)
                                                .clip(CircleShape)
                                                .background(qColor)
                                                .border(
                                                    width = if (cfg.color == qColor.toArgb()) 2.dp else 1.dp,
                                                    color = if (cfg.color == qColor.toArgb()) MiuixTheme.colorScheme.primary
                                                    else Color.Gray.copy(alpha = 0.4f),
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    Prefs.setAppNotifConfig(context, pkg, qColor.toArgb(), cfg.durationSec)
                                                    cfgTick++
                                                }
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = stringResource(
                                            R.string.title_notif_duration,
                                            cfg.durationSec ?: Prefs.getNotificationActivateDuration(context)
                                        ),
                                        fontSize = 11.sp,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MiuixTheme.colorScheme.surfaceVariant)
                                            .clickable {
                                                Prefs.setAppNotifConfig(context, pkg, cfg.color, null)
                                                cfgTick++
                                            }
                                            .padding(horizontal = 10.dp, vertical = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = stringResource(R.string.notif_app_reset),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = MiuixTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                                Slider(
                                    progress = (cfg.durationSec
                                        ?: Prefs.getNotificationActivateDuration(context)).toFloat(),
                                    onProgressChange = { value ->
                                        Prefs.setAppNotifConfig(
                                            context, pkg, cfg.color,
                                            value.toInt().coerceIn(1, 60)
                                        )
                                        cfgTick++
                                    },
                                    minValue = 1f,
                                    maxValue = 60f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable { onDismiss() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.btn_close),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
fun AudioTuningDialog(    onDismiss: () -> Unit,
    onResetDefaults: () -> Unit
) {
    val context = LocalContext.current
    val diag by AudioReactiveEngine.diagnostics.collectAsState()

    LaunchedEffect(Unit) { AudioReactiveEngine.ensurePreview(context) }
    DisposableEffect(Unit) { onDispose { AudioReactiveEngine.endPreview() } }

    var currentGain by remember { mutableFloatStateOf(AudioReactiveEngine.gainMultiplier) }
    var currentThreshold by remember { mutableFloatStateOf(AudioReactiveEngine.beatThreshold) }
    var currentMinHz by remember { mutableFloatStateOf(AudioReactiveEngine.minFreqHz) }
    var currentMaxHz by remember { mutableFloatStateOf(AudioReactiveEngine.maxFreqHz) }
    val currentSource by AudioReactiveEngine.sourceFlow.collectAsState()

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(18.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.audio_tuning_title),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MiuixTheme.colorScheme.onSurface
                )

                Text(
                    text = stringResource(R.string.audio_waveform_label, diag.fps),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(75.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val primaryColor = MiuixTheme.colorScheme.primary
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val w = size.width
                        val h = size.height
                        val midY = h / 2f
                        val points = diag.waveform
                        if (points.isNotEmpty()) {
                            val path = Path()
                            val step = w / (points.size - 1).coerceAtLeast(1)
                            for (i in points.indices) {
                                val x = i * step
                                val y = midY + points[i] * (h / 2.2f)
                                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                            }
                            drawPath(
                                path = path,
                                color = if (diag.hasAudioSignal) primaryColor else Color.Gray,
                                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                            )
                        }
                    }

                    if (!diag.hasAudioSignal) {
                        Text(
                            text = if (diag.captureError == AudioReactiveEngine.captureErrorGate) {
                                stringResource(R.string.audio_gate_closed)
                            } else if (diag.engineRunning) {
                                stringResource(R.string.audio_no_signal)
                            } else {
                                stringResource(R.string.audio_capture_failed, diag.captureError ?: "")
                            },
                            color = Color(0xFFFF5252),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audio_rms, (diag.level * 100).toInt()),
                        fontSize = 11.sp
                    )
                    Text(
                        text = stringResource(R.string.audio_band, (diag.targetEnergy * 100).toInt()),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(R.string.audio_bpm, diag.bpm),
                        fontSize = 11.sp
                    )
                    Text(
                        text = stringResource(R.string.audio_bps, diag.beatsPerSecond),
                        fontSize = 11.sp
                    )
                    Box(
                        modifier = Modifier
                            .clip(CircleShape)
                            .alpha(if (diag.isBeat) 1f else 0.35f)
                            .background(if (diag.isBeat) Color(0xFFFF1744) else Color.DarkGray)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (diag.isBeat) {
                                stringResource(R.string.audio_hit)
                            } else {
                                stringResource(R.string.audio_idle)
                            },
                            color = Color.White,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.audio_spectrum_label),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                val lowLabel = stringResource(R.string.audio_meter_low)
                val midLabel = stringResource(R.string.audio_meter_mid)
                val highLabel = stringResource(R.string.audio_meter_high)
                val meters = listOf(
                    Triple(lowLabel, diag.bass, Color(0xFFFF5252)),
                    Triple(midLabel, diag.mid, Color(0xFF69F0AE)),
                    Triple(highLabel, diag.treble, Color(0xFF448AFF))
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    meters.forEach { (label, value, barColor) ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(text = label, fontSize = 9.sp)
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(Color.DarkGray)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(value.coerceIn(0f, 1f))
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(barColor)
                                )
                            }
                        }
                    }
                }

                Text(
                    text = stringResource(R.string.audio_source),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        AudioReactiveEngine.SOURCE_GLOBAL_MIX to R.string.audio_source_global,
                        AudioReactiveEngine.SOURCE_MIC to R.string.audio_source_mic
                    ).forEach { (source, labelRes) ->
                        val selected = currentSource == source
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant
                                )
                                .semantics {
                                    contentDescription = context.getString(
                                        R.string.a11y_select,
                                        context.getString(labelRes)
                                    )
                                }
                                .clickable {
                                    AudioReactiveEngine.setSource(context, source)
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }

                SectionDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audio_source_band_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (diag.activeBand != AudioReactiveEngine.BAND_AUTO &&
                        diag.activeBand != AudioReactiveEngine.BAND_BROADBAND
                    ) {
                        val labelRes = when (diag.activeBand) {
                            AudioReactiveEngine.BAND_KICK -> R.string.audio_band_kick
                            AudioReactiveEngine.BAND_SNARE -> R.string.audio_band_snare
                            AudioReactiveEngine.BAND_VOCAL -> R.string.audio_band_vocal
                            AudioReactiveEngine.BAND_HAT -> R.string.audio_band_hat
                            else -> null
                        }
                        if (labelRes != null) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MiuixTheme.colorScheme.primary)
                                    .padding(horizontal = 8.dp, vertical = 3.dp)
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                val bandModeActive by AudioReactiveEngine.bandModeFlow.collectAsState()
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        AudioReactiveEngine.BAND_MODE_AUTO to R.string.audio_band_mode_auto,
                        AudioReactiveEngine.BAND_MODE_MANUAL to R.string.audio_band_mode_manual
                    ).forEach { (mode, labelRes) ->
                        val selected = bandModeActive == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (selected) MiuixTheme.colorScheme.primary
                                    else MiuixTheme.colorScheme.surfaceVariant
                                )
                                .semantics {
                                    contentDescription = context.getString(
                                        R.string.a11y_select,
                                        context.getString(labelRes)
                                    )
                                }
                                .clickable { AudioReactiveEngine.setBandMode(context, mode) }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = stringResource(labelRes),
                                fontSize = 11.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
                if (bandModeActive == AudioReactiveEngine.BAND_MODE_AUTO) {
                    Text(
                        text = stringResource(R.string.audio_band_mode_auto_desc),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
                if (bandModeActive == AudioReactiveEngine.BAND_MODE_MANUAL) {
                    Text(
                        text = stringResource(R.string.audio_band_selected, currentMinHz.toInt(), currentMaxHz.toInt()),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    val bandPresets = listOf(
                        R.string.audio_band_kick to (55f to 140f),
                        R.string.audio_band_snare to (200f to 600f),
                        R.string.audio_band_vocal to (800f to 2500f),
                        R.string.audio_band_hat to (5000f to 14000f)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        bandPresets.forEach { (labelRes, range) ->

                            val isSel = (Math.abs(currentMinHz - range.first) < 1f &&
                                    Math.abs(currentMaxHz - range.second) < 1f)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSel) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.surfaceVariant)
                                    .semantics {
                                        contentDescription = context.getString(
                                            R.string.a11y_select,
                                            context.getString(labelRes)
                                        )
                                    }
                                    .clickable {
                                        currentMinHz = range.first
                                        currentMaxHz = range.second
                                        AudioReactiveEngine.setFrequencyRange(context, range.first, range.second)
                                    }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    fontSize = 9.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSel) Color.White else MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    Text(
                        text = stringResource(R.string.audio_min_freq, currentMinHz.toInt()),
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        progress = currentMinHz,
                        onProgressChange = {
                            val clamped = it.coerceAtMost(currentMaxHz - 50f)
                            currentMinHz = clamped
                            AudioReactiveEngine.setFrequencyRange(context, clamped, currentMaxHz)
                        },
                        minValue = 20f,
                        maxValue = 10000f,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = stringResource(R.string.audio_max_freq, currentMaxHz.toInt()),
                        fontSize = 11.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Slider(
                        progress = currentMaxHz,
                        onProgressChange = {
                            val clamped = it.coerceAtLeast(currentMinHz + 50f)
                            currentMaxHz = clamped
                            AudioReactiveEngine.setFrequencyRange(context, currentMinHz, clamped)
                        },
                        minValue = 500f,
                        maxValue = 16000f,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                SectionDivider()
                val autoSensitivity by AudioReactiveEngine.autoSensitivityFlow.collectAsState()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.audio_sensitivity_title),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            true to R.string.audio_mode_auto,
                            false to R.string.audio_mode_manual
                        ).forEach { (auto, labelRes) ->
                            val selected = autoSensitivity == auto
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(
                                        if (selected) MiuixTheme.colorScheme.primary
                                        else MiuixTheme.colorScheme.surfaceVariant
                                    )
                                    .semantics {
                                        contentDescription = context.getString(
                                            R.string.a11y_select,
                                            context.getString(labelRes)
                                        )
                                    }
                                    .clickable {
                                        AudioReactiveEngine.setAutoSensitivity(context, auto)
                                    }
                                    .padding(horizontal = 12.dp, vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(labelRes),
                                    fontSize = 10.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) Color.White else MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }

                if (autoSensitivity) {
                    Text(
                        text = stringResource(R.string.audio_sensitivity_auto_desc),
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }

                if (autoSensitivity) {
                    LaunchedEffect(diag.threshold) { currentThreshold = diag.threshold }
                    LaunchedEffect(diag.gainScale) { currentGain = diag.gainScale }
                }
                Text(
                    text = stringResource(R.string.audio_gain, "%.2f".format(currentGain)),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Slider(
                    progress = currentGain,
                    onProgressChange = {
                        currentGain = it
                        AudioReactiveEngine.setGain(context, it)
                    },
                    enabled = !autoSensitivity,
                    minValue = 0.2f,
                    maxValue = 3.0f,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = stringResource(R.string.audio_beat_threshold, "%.2f".format(currentThreshold)),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Slider(
                    progress = currentThreshold,
                    onProgressChange = {
                        currentThreshold = it
                        AudioReactiveEngine.setBeatThreshold(context, it)
                    },
                    enabled = !autoSensitivity,
                    minValue = 1.05f,
                    maxValue = 1.6f,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(4.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.surfaceVariant)
                        .clickable {

                            currentGain = 0.7f
                            currentThreshold = 1.18f
                            currentMinHz = 5000f
                            currentMaxHz = 14000f
                            AudioReactiveEngine.setSource(context, AudioReactiveEngine.SOURCE_GLOBAL_MIX)
                            AudioReactiveEngine.setGain(context, 0.7f)
                            AudioReactiveEngine.setBeatThreshold(context, 1.18f)
                            AudioReactiveEngine.setFrequencyRange(context, 5000f, 14000f)
                            AudioReactiveEngine.setBandMode(context, AudioReactiveEngine.BAND_MODE_AUTO)
                            AudioReactiveEngine.setAutoSensitivity(context, true)
                            AudioReactiveEngine.setDegreesPerBeat(
                                context, AudioReactiveEngine.DEFAULT_DEGREES_PER_BEAT
                            )
                            onResetDefaults()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.audio_reset),
                        color = MiuixTheme.colorScheme.onSurface,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primary)
                        .clickable { onDismiss() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.btn_close),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}
