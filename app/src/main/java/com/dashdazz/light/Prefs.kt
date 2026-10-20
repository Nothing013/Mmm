package com.dashdazz.light

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color

object Prefs {
    private const val PREFS_NAME = "dashdazz_prefs"

    private const val KEY_MODE = "key_mode"
    private const val KEY_COLOR = "key_color"
    private const val KEY_SPEED = "key_speed"
    private const val KEY_BRIGHTNESS = "key_brightness"
    private const val KEY_PAUSE_ON_SCREEN_OFF = "key_pause_screen_off"
    private const val KEY_SCREEN_MODE = "key_screen_mode"
    private const val KEY_BLINK_ON_NOTIFICATION = "key_blink_notification"
    private const val KEY_PALETTE = "key_palette"
    private const val KEY_DARK_MODE = "key_dark_mode"
    private const val KEY_SETUP_DONE = "key_setup_done"
    private const val KEY_DUAL_STYLE = "key_dual_style"
    private const val KEY_SPLIT_COLOR_A = "key_split_color_a"
    private const val KEY_SPLIT_COLOR_B = "key_split_color_b"
    private const val KEY_SPIN_COLOR_A = "key_spin_color_a"
    private const val KEY_SPIN_COLOR_B = "key_spin_color_b"
    private const val KEY_DUO_OFFSET = "key_duo_hue_offset"
    private const val KEY_CUSTOM_PRESETS = "key_custom_presets"
    private const val KEY_NOTIF_MODE = "key_notif_mode"
    private const val KEY_NOTIF_ACTIVATE_DURATION = "key_notif_activate_duration"
    private const val KEY_AUDIO_REACTIVE = "key_audio_reactive"

    private const val KEY_AUDIO_MODIFIER_MIGRATED = "key_audio_modifier_migrated"

    private const val KEY_AUDIO_SOURCE = "key_audio_source"
    private const val KEY_AUDIO_GAIN = "key_audio_gain"
    private const val KEY_AUDIO_THRESHOLD = "key_audio_beat_threshold"
    private const val KEY_AUDIO_MIN_HZ = "key_audio_min_hz"
    private const val KEY_AUDIO_MAX_HZ = "key_audio_max_hz"
    private const val KEY_AUDIO_BAND_MODE = "key_audio_band_mode"
    private const val KEY_AUDIO_DEGREES_PER_BEAT = "key_audio_degrees_per_beat"
    private const val KEY_AUDIO_AUTO_SENSITIVITY = "key_audio_auto_sensitivity"
    private const val KEY_AUDIO_APPS = "key_audio_apps"
    private const val KEY_AUDIO_APPS_FILTER = "key_audio_apps_filter"
    private const val KEY_AUTO_CHARGING = "key_auto_charging"
    private const val KEY_UPDATE_LAST_CHECK_MS = "key_update_last_check_ms"
    private const val KEY_UPDATE_PENDING_APK = "key_update_pending_apk"
    private const val KEY_PENDING_NOTIF_MODE = "key_pending_notif_mode"

    data class AudioConfig(
        val source: Int,
        val gain: Float,
        val threshold: Float,
        val minHz: Float,
        val maxHz: Float,
        val bandMode: Int,
        val degreesPerBeat: Float,
        val autoSensitivity: Boolean
    )

    fun audioConfig(context: Context): AudioConfig {
        val p = prefs(context)
        val minHz = p.getFloat(KEY_AUDIO_MIN_HZ, AudioReactiveEngine.MIN_HZ_DEFAULT)
            .coerceIn(20f, 20000f)
        val maxHz = p.getFloat(KEY_AUDIO_MAX_HZ, AudioReactiveEngine.MAX_HZ_DEFAULT)
            .coerceIn(20f, 20000f)
        val (lo, hi) = if (minHz <= maxHz) minHz to maxHz else maxHz to minHz
        return AudioConfig(
            source = p.getInt(KEY_AUDIO_SOURCE, AudioReactiveEngine.SOURCE_GLOBAL_MIX)
                .coerceIn(0, 1),
            gain = p.getFloat(KEY_AUDIO_GAIN, 0.7f).coerceIn(0.1f, 3f),
            threshold = p.getFloat(KEY_AUDIO_THRESHOLD, 1.18f).coerceIn(1.02f, 2f),
            minHz = lo,
            maxHz = hi,
            bandMode = p.getInt(KEY_AUDIO_BAND_MODE, AudioReactiveEngine.BAND_MODE_AUTO)
                .coerceIn(0, 1),
            degreesPerBeat = p.getFloat(KEY_AUDIO_DEGREES_PER_BEAT, AudioReactiveEngine.DEFAULT_DEGREES_PER_BEAT)
                .coerceIn(AudioReactiveEngine.MIN_DEGREES_PER_BEAT, AudioReactiveEngine.MAX_DEGREES_PER_BEAT),
            autoSensitivity = p.getBoolean(KEY_AUDIO_AUTO_SENSITIVITY, true)
        )
    }

    fun setAudioSource(context: Context, source: Int) {
        prefs(context).edit().putInt(KEY_AUDIO_SOURCE, source.coerceIn(0, 1)).apply()
    }
    fun setAudioGain(context: Context, gain: Float) {
        prefs(context).edit().putFloat(KEY_AUDIO_GAIN, gain.coerceIn(0.1f, 3f)).apply()
    }

    fun setAudioBeatThreshold(context: Context, threshold: Float) {
        prefs(context).edit().putFloat(KEY_AUDIO_THRESHOLD, threshold.coerceIn(1.02f, 2f)).apply()
    }

    fun setAudioBand(context: Context, minHz: Float, maxHz: Float) {
        val lo = minHz.coerceIn(20f, 20000f)
        val hi = maxHz.coerceIn(20f, 20000f)
        prefs(context).edit()
            .putFloat(KEY_AUDIO_MIN_HZ, minOf(lo, hi))
            .putFloat(KEY_AUDIO_MAX_HZ, maxOf(lo, hi))
            .apply()
    }
    fun setAudioBandMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_AUDIO_BAND_MODE, mode.coerceIn(0, 1)).apply()
    }

    fun setAudioAutoSensitivity(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_AUTO_SENSITIVITY, enabled).apply()
    }

    fun setAudioDegreesPerBeat(context: Context, degrees: Float) {
        prefs(context).edit().putFloat(
            KEY_AUDIO_DEGREES_PER_BEAT,
            degrees.coerceIn(AudioReactiveEngine.MIN_DEGREES_PER_BEAT, AudioReactiveEngine.MAX_DEGREES_PER_BEAT)
        ).apply()
    }

    const val NOTIF_MODE_OFF = 0
    const val NOTIF_MODE_ACTIVATE = 1

    private fun prefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getMode(context: Context, default: Int = DashDazzService.MODE_RAINBOW): Int {
        return prefs(context).getInt(KEY_MODE, default)
    }

    fun setMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_MODE, mode).apply()
    }

    fun getColor(context: Context, default: Int = Color.RED): Int {
        return prefs(context).getInt(KEY_COLOR, default)
    }


    fun getSpeedMs(context: Context, default: Long = 25L): Long {
        return prefs(context).getLong(KEY_SPEED, default)
    }


    fun getBrightness(context: Context, default: Float = 1.0f): Float {
        return prefs(context).getFloat(KEY_BRIGHTNESS, default)
    }


    fun isPauseOnScreenOff(context: Context, default: Boolean = true): Boolean {
        return prefs(context).getBoolean(KEY_PAUSE_ON_SCREEN_OFF, default)
    }


    fun getScreenMode(context: Context): Int {
        val p = prefs(context)
        if (p.contains(KEY_SCREEN_MODE)) return p.getInt(KEY_SCREEN_MODE, DashDazzService.SCREEN_ON_ONLY)
        return if (isPauseOnScreenOff(context)) DashDazzService.SCREEN_ON_ONLY else DashDazzService.SCREEN_ALWAYS
    }


    fun isSetupDone(context: Context): Boolean = prefs(context).getBoolean(KEY_SETUP_DONE, false)

    fun setSetupDone(context: Context, done: Boolean) {
        prefs(context).edit().putBoolean(KEY_SETUP_DONE, done).apply()
    }

    fun getDuoHueOffset(context: Context, default: Int = 90): Int {
        return prefs(context).getInt(KEY_DUO_OFFSET, default)
    }


    fun isBlinkOnNotification(context: Context, default: Boolean = false): Boolean {
        return prefs(context).getBoolean(KEY_BLINK_ON_NOTIFICATION, default)
    }


    fun getPalette(context: Context, default: Int = Palettes.SPECTRUM): Int {
        return prefs(context).getInt(KEY_PALETTE, default)
    }


    fun getDualStyle(context: Context, default: Int = DashDazzService.DUAL_HARDWARE_SPLIT): Int {
        return prefs(context).getInt(KEY_DUAL_STYLE, default)
    }

    fun setDualStyle(context: Context, style: Int) {
        prefs(context).edit().putInt(KEY_DUAL_STYLE, style).apply()
    }

    fun getSplitColorA(context: Context, default: Int = Color.RED): Int {
        return prefs(context).getInt(KEY_SPLIT_COLOR_A, default)
    }


    fun getSplitColorB(context: Context, default: Int = Color.BLUE): Int {
        return prefs(context).getInt(KEY_SPLIT_COLOR_B, default)
    }


    fun getSpinColorA(context: Context, default: Int = Color.RED): Int {
        return prefs(context).getInt(KEY_SPIN_COLOR_A, default)
    }


    fun getSpinColorB(context: Context, default: Int = Color.BLUE): Int {
        return prefs(context).getInt(KEY_SPIN_COLOR_B, default)
    }


    fun getDarkMode(context: Context, default: Int = 0): Int {
        return prefs(context).getInt(KEY_DARK_MODE, default)
    }

    fun setDarkMode(context: Context, mode: Int) {
        prefs(context).edit().putInt(KEY_DARK_MODE, mode).apply()
    }

    const val MAX_CUSTOM_PRESETS = 24
    private const val KEY_CONFLICT_DISMISSED = "key_conflict_dismissed"
    private const val KEY_FRAME_MIN_MS = "key_frame_min_ms"
    private const val KEY_NOTIF_APPS = "key_notif_apps"
    private const val KEY_NOTIF_APPS_FILTER = "key_notif_apps_filter"
    private const val KEY_FLASH_ON_LOCK = "key_flash_on_lock"
    private const val KEY_FLASH_ON_UNLOCK = "key_flash_on_unlock"
    private const val KEY_FLASH_SCREEN = "key_flash_screen"
    private const val KEY_NOTIF_APP_CFG = "key_notif_app_cfg"

    data class AppNotifConfig(val color: Int?, val durationSec: Int?)

    const val FRAME_SMOOTH_MS = 20L
    const val FRAME_BALANCED_MS = 33L
    const val FRAME_ECO_MS = 66L

    fun isConflictDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFLICT_DISMISSED, false)

    fun setConflictDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFLICT_DISMISSED, dismissed).apply()
    }

    fun getFrameMinMs(context: Context): Long =
        prefs(context).getLong(KEY_FRAME_MIN_MS, FRAME_SMOOTH_MS).coerceIn(15L, 100L)

    fun getNotifApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_NOTIF_APPS, emptySet())?.toSet() ?: emptySet()

    fun setNotifApps(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_NOTIF_APPS, pkgs.toSet()).apply()
    }

    fun isNotifAppsFilter(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIF_APPS_FILTER, false)

    fun migrateLegacyFilters(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_NOTIF_APPS_FILTER) &&
            !p.getStringSet(KEY_NOTIF_APPS, emptySet()).isNullOrEmpty()
        ) {
            p.edit().putBoolean(KEY_NOTIF_APPS_FILTER, true).apply()
        }
        if (!p.contains(KEY_AUDIO_APPS_FILTER) &&
            !p.getStringSet(KEY_AUDIO_APPS, emptySet()).isNullOrEmpty()
        ) {
            p.edit().putBoolean(KEY_AUDIO_APPS_FILTER, true).apply()
        }
    }

    fun setNotifAppsFilter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIF_APPS_FILTER, enabled).apply()
    }

    fun getAudioApps(context: Context): Set<String> =
        prefs(context).getStringSet(KEY_AUDIO_APPS, emptySet())?.toSet() ?: emptySet()

    fun setAudioApps(context: Context, pkgs: Set<String>) {
        prefs(context).edit().putStringSet(KEY_AUDIO_APPS, pkgs.toSet()).apply()
    }

    fun isAudioAppsFilter(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_APPS_FILTER, false)

    fun setAudioAppsFilter(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_APPS_FILTER, enabled).apply()
    }

    fun isAutoCharging(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHARGING, false)

    fun setAutoCharging(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHARGING, enabled).apply()
    }

    fun getUpdateLastCheckMs(context: Context): Long =
        prefs(context).getLong(KEY_UPDATE_LAST_CHECK_MS, 0L)

    fun setUpdateLastCheckMs(context: Context, ms: Long) {
        prefs(context).edit().putLong(KEY_UPDATE_LAST_CHECK_MS, ms).apply()
    }

    fun getPendingUpdateApk(context: Context): String? =
        prefs(context).getString(KEY_UPDATE_PENDING_APK, null)

    fun setPendingUpdateApk(context: Context, path: String?) {
        prefs(context).edit().putString(KEY_UPDATE_PENDING_APK, path).apply()
    }

    fun getPendingNotifMode(context: Context): Int? {
        val v = prefs(context).getInt(KEY_PENDING_NOTIF_MODE, -1)
        return if (v < 0) null else v
    }

    fun setPendingNotifMode(context: Context, mode: Int?) {
        prefs(context).edit().putInt(KEY_PENDING_NOTIF_MODE, mode ?: -1).apply()
    }



    fun isFlashScreen(context: Context): Boolean =
        prefs(context).getBoolean(KEY_FLASH_SCREEN, false)

    fun setFlashScreen(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_FLASH_SCREEN, enabled).apply()
    }

    fun migrateLegacyFlashToggles(context: Context) {
        val p = prefs(context)
        if (p.contains(KEY_FLASH_SCREEN)) return
        val legacy = p.getBoolean(KEY_FLASH_ON_LOCK, false) ||
                p.getBoolean(KEY_FLASH_ON_UNLOCK, false)
        p.edit().putBoolean(KEY_FLASH_SCREEN, legacy).apply()
    }

    fun getAppNotifConfig(context: Context, pkg: String): AppNotifConfig {
        val raw = prefs(context).getString(KEY_NOTIF_APP_CFG, null) ?: return AppNotifConfig(null, null)
        for (entry in raw.split(";")) {
            val kv = entry.split("=", limit = 2)
            if (kv.size != 2 || kv[0] != pkg) continue
            val parts = kv[1].split(",")
            val color = parts.getOrNull(0)?.toIntOrNull()?.takeIf { it != 0 }
            val duration = parts.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..60 }
            return AppNotifConfig(color, duration)
        }
        return AppNotifConfig(null, null)
    }

    fun setAppNotifConfig(context: Context, pkg: String, color: Int?, durationSec: Int?) {
        val map = LinkedHashMap<String, Pair<Int?, Int?>>()
        val raw = prefs(context).getString(KEY_NOTIF_APP_CFG, null)
        if (raw != null) {
            for (entry in raw.split(";")) {
                val kv = entry.split("=", limit = 2)
                if (kv.size != 2) continue
                val parts = kv[1].split(",")
                map[kv[0]] = parts.getOrNull(0)?.toIntOrNull() to parts.getOrNull(1)?.toIntOrNull()
            }
        }
        if (color == null && durationSec == null) {
            map.remove(pkg)
        } else {
            map[pkg] = (color ?: 0) to (durationSec ?: -1)
        }
        val encoded = map.entries.joinToString(";") { (k, v) -> "$k=${v.first ?: 0},${v.second ?: -1}" }
        prefs(context).edit().putString(KEY_NOTIF_APP_CFG, encoded).apply()
    }

    fun getCustomPresets(context: Context): List<Pair<Float, Float>> {
        val raw = prefs(context).getString(KEY_CUSTOM_PRESETS, null) ?: return emptyList()
        return raw.split(";").filter { it.contains(",") }.mapNotNull {
            val parts = it.split(",")
            val h = parts.getOrNull(0)?.toFloatOrNull()
            val s = parts.getOrNull(1)?.toFloatOrNull()
            if (h != null && s != null) Pair(h, s) else null
        }
    }

    fun saveCustomPreset(context: Context, hue: Float, saturation: Float) {
        val h = hue.coerceIn(0f, 360f)
        val s = saturation.coerceIn(0f, 1f)
        val current = getCustomPresets(context).toMutableList()
        if (current.any { Math.abs(it.first - h) < 2f && Math.abs(it.second - s) < 0.03f }) {
            return
        }
        current.add(Pair(h, s))
        while (current.size > MAX_CUSTOM_PRESETS) current.removeAt(0)
        val encoded = current.joinToString(";") { "${it.first},${it.second}" }
        prefs(context).edit().putString(KEY_CUSTOM_PRESETS, encoded).apply()
    }

    fun removeCustomPreset(context: Context, index: Int) {
        val current = getCustomPresets(context).toMutableList()
        if (index in current.indices) {
            current.removeAt(index)
            val encoded = current.joinToString(";") { "${it.first},${it.second}" }
            prefs(context).edit().putString(KEY_CUSTOM_PRESETS, encoded).apply()
        }
    }

    fun getNotificationMode(context: Context, default: Int = NOTIF_MODE_OFF): Int {
        return prefs(context).getInt(KEY_NOTIF_MODE, default)
            .coerceIn(NOTIF_MODE_OFF, NOTIF_MODE_ACTIVATE)
    }

    fun migrateLegacyNotificationMode(context: Context) {
        val p = prefs(context)
        if (!p.contains(KEY_NOTIF_MODE) && p.getBoolean(KEY_BLINK_ON_NOTIFICATION, false)) {
            p.edit().putInt(KEY_NOTIF_MODE, NOTIF_MODE_ACTIVATE).apply()
        }
    }


    fun getNotificationActivateDuration(context: Context, default: Int = 5): Int {
        return prefs(context).getInt(KEY_NOTIF_ACTIVATE_DURATION, default).coerceIn(1, 60)
    }


    fun isAudioReactive(context: Context, default: Boolean = false): Boolean =
        prefs(context).getBoolean(KEY_AUDIO_REACTIVE, default)

    fun setAudioReactive(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO_REACTIVE, enabled).apply()
    }

    fun needsAudioModifierMigration(context: Context): Int? {
        val p = prefs(context)
        if (p.getBoolean(KEY_AUDIO_MODIFIER_MIGRATED, false)) return null
        val saved = p.getInt(KEY_MODE, -1)
        if (saved != DashDazzService.MODE_AUDIO_REACTIVE) return null

        p.edit()
            .putInt(KEY_MODE, DashDazzService.MODE_RAINBOW)
            .putBoolean(KEY_AUDIO_REACTIVE, true)
            .putBoolean(KEY_AUDIO_MODIFIER_MIGRATED, true)
            .apply()
        return DashDazzService.MODE_RAINBOW
    }


    fun saveAll(context: Context, s: DashDazzState) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt(KEY_MODE, s.mode)
            .putInt(KEY_COLOR, s.color)
            .putLong(KEY_SPEED, s.speedMs.coerceIn(10L, 500L))
            .putFloat(KEY_BRIGHTNESS, if (s.brightness.isNaN()) 1f else s.brightness.coerceIn(0.05f, 1f))
            .putInt(KEY_SCREEN_MODE, s.screenMode.coerceIn(0, 3))
            .putBoolean(KEY_BLINK_ON_NOTIFICATION, s.blinkOnNotification)
            .putInt(KEY_PALETTE, s.palette)
            .putInt(KEY_DUAL_STYLE, s.dualStyle)
            .putInt(KEY_SPLIT_COLOR_A, s.splitColorA)
            .putInt(KEY_SPLIT_COLOR_B, s.splitColorB)
            .putInt(KEY_SPIN_COLOR_A, s.spinColorA)
            .putInt(KEY_SPIN_COLOR_B, s.spinColorB)
            .putInt(KEY_DUO_OFFSET, s.duoHueOffset.coerceIn(0, 360))
            .putInt(KEY_NOTIF_MODE, s.notifMode.coerceIn(NOTIF_MODE_OFF, NOTIF_MODE_ACTIVATE))
            .putInt(KEY_NOTIF_ACTIVATE_DURATION, s.notifActivateDuration.coerceIn(1, 60))
            .putBoolean(KEY_AUDIO_REACTIVE, s.audioReactive)
            .putLong(KEY_FRAME_MIN_MS, s.frameMinMs.coerceIn(15L, 100L))
            .apply()
    }

    fun loadState(context: Context): DashDazzState {
        return DashDazzState(
            mode = getMode(context),
            color = getColor(context),
            speedMs = getSpeedMs(context),
            brightness = getBrightness(context),
            screenMode = getScreenMode(context),
            blinkOnNotification = isBlinkOnNotification(context),
            palette = getPalette(context),
            dualStyle = getDualStyle(context),
            splitColorA = getSplitColorA(context),
            splitColorB = getSplitColorB(context),
            spinColorA = getSpinColorA(context),
            spinColorB = getSpinColorB(context),
            duoHueOffset = getDuoHueOffset(context),
            notifMode = getNotificationMode(context),
            notifActivateDuration = getNotificationActivateDuration(context),
            audioReactive = isAudioReactive(context),
            frameMinMs = getFrameMinMs(context)
        )
    }
}
