package com.dashdazz.light

import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class DashDazzTileService : TileService() {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var observeJob: Job? = null
    @Volatile
    private var lastClickMs = 0L

    override fun onStartListening() {
        super.onStartListening()
        updateTileState(DashDazzService.stateFlow.value)

        observeJob?.cancel()
        observeJob = DashDazzService.stateFlow
            .onEach { state ->
                updateTileState(state)
            }
            .launchIn(scope)
    }

    override fun onStopListening() {
        observeJob?.cancel()
        super.onStopListening()
    }

    override fun onDestroy() {
        observeJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()

        val now = SystemClock.elapsedRealtime()
        if (now - lastClickMs < 800L) return
        lastClickMs = now

        if (DashDazzService.isRunning) {
            DashDazzService.stop(this)
            return
        }

        scope.launch {
            val canRun = withContext(Dispatchers.IO) {
                LightsBridge.isRootAvailable() ||
                        (LightsBridge.isShizukuAvailable() && LightsBridge.hasShizukuPermission())
            }
            if (!canRun) {
                openLauncher()
                return@launch
            }
            DashDazzService.start(this@DashDazzTileService)
        }
    }

    private fun openLauncher() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        @Suppress("DEPRECATION")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && isLocked) {
                unlockAndRun { startActivityAndCollapse(intent) }
            } else {
                startActivityAndCollapse(intent)
            }
        } catch (_: Throwable) {
            try {
                startActivity(intent)
            } catch (_: Throwable) {
            }
        }
    }

    private var lastSubtitle: String? = null

    private fun updateTileState(state: DashDazzState) {
        val subtitle = describeState(state)
        if (lastSubtitle == subtitle && qsTile != null) {
            return
        }
        lastSubtitle = subtitle
        val tile = qsTile ?: return

        tile.state = if (state.isRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = getString(R.string.qs_tile_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_qs_camera_ring)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = subtitle
        }
        tile.updateTile()
    }

    private fun describeState(state: DashDazzState): String {
        if (!state.isRunning) return getString(R.string.status_service_stopped)
        if (state.isScreenPaused) return getString(R.string.notification_paused)
        val mode = if (state.chargingIndicator) {
            getString(R.string.mode_charging)
        } else when (state.mode) {
            DashDazzService.MODE_BREATHE -> getString(R.string.mode_breathe)
            DashDazzService.MODE_SOLID -> getString(R.string.mode_solid)
            DashDazzService.MODE_DUAL -> getString(R.string.mode_dual)
            else -> getString(R.string.mode_rainbow)
        }
        val detail = if (state.mode == DashDazzService.MODE_DUAL) {
            when (state.dualStyle) {
                DashDazzService.DUAL_SPIN -> getString(R.string.dual_spin)
                DashDazzService.DUAL_POLICE -> getString(R.string.dual_police)
                DashDazzService.DUAL_ALTERNATE -> getString(R.string.dual_alternate)
                DashDazzService.DUAL_RAINBOW_DUO -> getString(R.string.dual_rainbow_duo)
                DashDazzService.DUAL_BEAT_RAINBOW -> getString(R.string.dual_beat_rainbow)
                DashDazzService.DUAL_SPLIT_BEAT -> getString(R.string.dual_split_beat)
                else -> getString(R.string.dual_hardware_split)
            }
        } else null
        val audio = if (state.audioReactive) getString(R.string.notif_audio_suffix) else ""
        return mode + (detail?.let { " $it" } ?: "") + audio
    }
}
