package com.dashdazz.light

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class DashDazzNotificationListener : NotificationListenerService() {

    private val listenerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var mediaWasPlaying = false

    @Volatile
    private var mediaReclaimJob: kotlinx.coroutines.Job? = null

    @Volatile
    private var activeControllers: List<MediaController> = emptyList()

    private val controllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            val sessions = getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(this@DashDazzNotificationListener, DashDazzNotificationListener::class.java))
            handleMediaSessionChange(sessions ?: activeControllers)
        }
    }

    private val mediaSessionListener = object : MediaSessionManager.OnActiveSessionsChangedListener {
        override fun onActiveSessionsChanged(controllers: List<MediaController>?) {
            updateControllerCallbacks(controllers)
            handleMediaSessionChange(controllers)
        }
    }

    private fun updateControllerCallbacks(newControllers: List<MediaController>?) {
        for (ctrl in activeControllers) {
            try {
                ctrl.unregisterCallback(controllerCallback)
            } catch (_: Throwable) {
            }
        }
        val next = newControllers ?: emptyList()
        for (ctrl in next) {
            try {
                ctrl.registerCallback(controllerCallback)
            } catch (_: Throwable) {
            }
        }
        activeControllers = next
    }

    @Volatile
    private var sessionListenerRegistered = false

    private fun playingPackagesOf(controllers: List<MediaController>?): Set<String> =
        controllers?.mapNotNull { ctrl ->
            if (ctrl.playbackState?.state == PlaybackState.STATE_PLAYING) ctrl.packageName else null
        }?.toSet() ?: emptySet()

    private fun queryPlayingPackages(): Set<String>? {
        return try {
            val sessions = getSystemService(MediaSessionManager::class.java)
                ?.getActiveSessions(ComponentName(this, DashDazzNotificationListener::class.java))
                ?: return null
            sessions.mapNotNull { c ->
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.packageName else null
            }.toSet()
        } catch (e: Throwable) {
            null
        }
    }

    private fun publishPlayingPackages(pkgs: Set<String>) {
        if (pkgs == playingPackages) return
        playingPackages = pkgs
        if (listenerScope.isActive) {
            listenerScope.launch { AudioReactiveEngine.refreshAudioGate() }
        } else {
            AudioReactiveEngine.refreshAudioGate()
        }
    }

    private fun handleMediaSessionChange(controllers: List<MediaController>?) {
        val playingNow = playingPackagesOf(controllers)
        val anyPlaying = playingNow.isNotEmpty()
        publishPlayingPackages(playingNow)
        DashDazzService.onMediaPlaybackChanged(anyPlaying)

        if (anyPlaying && !mediaWasPlaying) {
            Log.d(TAG, "Media playback started: scheduling scene reclaims")
            mediaReclaimJob?.cancel()
            mediaReclaimJob = listenerScope.launch {
                var elapsed = 0L
                for (offsetMs in RECLAIM_OFFSETS_MS) {
                    delay(offsetMs - elapsed)
                    elapsed = offsetMs
                    val stillPlayingPkgs = queryPlayingPackages()
                    if (stillPlayingPkgs == null) {
                        Log.d(TAG, "Reclaim poll failed, keeping previous playback state")
                        continue
                    }
                    val stillPlaying = stillPlayingPkgs.isNotEmpty()
                    publishPlayingPackages(stillPlayingPkgs)
                    DashDazzService.onMediaPlaybackChanged(stillPlaying)
                    if (!stillPlaying) break
                    if (DashDazzService.isRunning && !DashDazzService.stateFlow.value.isScreenPaused) {
                        LightsBridge.resetScene()
                    }
                }
            }
        } else if (!anyPlaying && mediaWasPlaying) {
            Log.d(TAG, "Media playback stopped: reclaiming LED control")
            listenerScope.launch {
                delay(300)
                if (DashDazzService.isRunning && !DashDazzService.stateFlow.value.isScreenPaused) {
                    LightsBridge.resetScene()
                }
            }
        }
        mediaWasPlaying = anyPlaying
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            val component = ComponentName(this, DashDazzNotificationListener::class.java)
            if (!sessionListenerRegistered) {
                msm?.addOnActiveSessionsChangedListener(mediaSessionListener, component)
                sessionListenerRegistered = msm != null
            }

            val initialSessions = msm?.getActiveSessions(component)
            updateControllerCallbacks(initialSessions)
            handleMediaSessionChange(initialSessions)
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access MediaSessionManager — notification listener permission may be insufficient", e)
        } catch (e: Throwable) {
            Log.w(TAG, "MediaSessionManager setup failed", e)
        }
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        updateControllerCallbacks(null)
        mediaReclaimJob?.cancel()
        mediaReclaimJob = null
        mediaWasPlaying = false
        publishPlayingPackages(emptySet())
        DashDazzService.onMediaPlaybackChanged(false)
        try {
            requestRebind(ComponentName(this, DashDazzNotificationListener::class.java))
        } catch (e: Throwable) {
            Log.w(TAG, "Listener rebind failed", e)
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return

        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_NO_CLEAR) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val allowedApps = Prefs.getNotifApps(this)
        if (Prefs.isNotifAppsFilter(this) && sbn.packageName !in allowedApps) return

        val durationSec = Prefs.getNotificationActivateDuration(this)
        val cfg = Prefs.getAppNotifConfig(this, sbn.packageName)
        Log.d(TAG, "Notification received from ${sbn.packageName}, activating lights for ${durationSec}s")
        if (cfg.color != null) {
            val holdMs = ((cfg.durationSec ?: durationSec).coerceIn(1, 60)) * 1000L
            DashDazzService.pulseNotification(this, cfg.color, holdMs)
        } else {
            DashDazzService.activateForNotification(this, durationSec)
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        if (Prefs.isNotifAppsFilter(this) && sbn.packageName !in Prefs.getNotifApps(this)) return
        if (Prefs.getNotificationMode(this) != Prefs.NOTIF_MODE_ACTIVATE) return

        listenerScope.launch {
            delay(500)
            if (DashDazzService.isRunning && !DashDazzService.stateFlow.value.isScreenPaused) {
                LightsBridge.resetScene()
            }
        }
    }

    override fun onDestroy() {
        updateControllerCallbacks(null)
        try {
            val msm = getSystemService(MediaSessionManager::class.java)
            msm?.removeOnActiveSessionsChangedListener(mediaSessionListener)
        } catch (e: Throwable) {
            Log.w(TAG, "Removing media session listener failed", e)
        }
        mediaReclaimJob?.cancel()
        mediaReclaimJob = null
        mediaWasPlaying = false
        publishPlayingPackages(emptySet())
        DashDazzService.onMediaPlaybackChanged(false)
        listenerScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DashDazzNotif"

        private val RECLAIM_OFFSETS_MS = listOf(500L, 1500L, 3000L, 5000L, 8000L)

        @Volatile
        var playingPackages: Set<String> = emptySet()
            private set

        fun isNotificationAccessGranted(context: Context): Boolean {
            val enabledListeners = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false

            val mine = ComponentName(context, DashDazzNotificationListener::class.java)
            return enabledListeners.split(":").any { entry ->
                runCatching {
                    ComponentName.unflattenFromString(entry) == mine
                }.getOrDefault(false)
            }
        }

        fun openNotificationAccessSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}
