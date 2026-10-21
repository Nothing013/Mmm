package com.dashdazz.light

import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.dashdazz.light.lightsapi.ILightsManager
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper
import java.util.concurrent.TimeUnit

object LightsBridge {
    private const val TAG = "DashDazz"
    private const val CAMERA_PKG = "com.android.camera"
    private const val STYLE_MUSIC = 3
    private const val STYLE_RESET = -1

    private const val ROOT_TIMEOUT_SEC = 5L
    private const val ROOT_CACHE_TTL_MS = 30_000L

    @Volatile
    private var cachedManager: ILightsManager? = null

    @Volatile
    private var cachedWrapper: IBinder? = null

    private val deathRecipient = IBinder.DeathRecipient {

        val wrapper = cachedWrapper
        if (wrapper != null && !wrapper.isBinderAlive) {
            Log.w(TAG, "LightsManager binder died, clearing cache")
            cachedManager = null
            cachedWrapper = null
        }
    }

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Throwable) {
            false
        }
    }

    fun hasShizukuPermission(): Boolean {
        return try {
            isShizukuAvailable() &&
                    Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED
        } catch (_: Throwable) {
            false
        }
    }

    fun getLightsManager(): ILightsManager? {
        val current = cachedManager
        try {
            if (current != null && current.asBinder().isBinderAlive) {
                return current
            }
        } catch (_: Throwable) {
            cachedManager = null
            cachedWrapper = null
        }

        synchronized(this) {
            val existing = cachedManager
            try {
                if (existing != null && existing.asBinder().isBinderAlive) {
                    return existing
                }
            } catch (_: Throwable) {
                cachedManager = null
                cachedWrapper = null
            }

            if (!isShizukuAvailable()) return null
            if (!hasShizukuPermission()) return null

            try {
                val raw = SystemServiceHelper.getSystemService(ILightsManager.DESCRIPTOR) ?: return null
                val wrapper = ShizukuBinderWrapper(raw)
                try {
                    wrapper.linkToDeath(deathRecipient, 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to linkToDeath on wrapper", e)
                }

                try {
                    (cachedWrapper as? ShizukuBinderWrapper)?.unlinkToDeath(deathRecipient, 0)
                } catch (_: Throwable) {
                }
                val manager = ILightsManager.Stub.asInterface(wrapper)
                cachedManager = manager
                cachedWrapper = wrapper
                return manager
            } catch (e: Exception) {
                Log.e(TAG, "Error acquiring ILightsManager", e)
                return null
            }
        }
    }

    fun invalidateBinderCache() {
        try {
            (cachedWrapper as? ShizukuBinderWrapper)?.unlinkToDeath(deathRecipient, 0)
        } catch (_: Throwable) {
        }
        cachedManager = null
        cachedWrapper = null
    }

    private const val SYS_REG = "/sys/class/leds/aw21024_led/reg"
    private const val SYS_HWEN = "/sys/class/leds/aw21024_led/hwen"
    private const val SYS_EFFECT = "/sys/class/leds/aw21024_led/effect"

    @Volatile
    private var isRootCached: Boolean? = null
    @Volatile
    private var rootCacheAtMs: Long = 0L

    private class ProcessResult(val finished: Boolean, val exitCode: Int, val output: String)

    private fun runProcess(vararg cmd: String): ProcessResult {
        var p: Process? = null
        return try {
            p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
            val collected = StringBuilder()
            val drain = Thread {
                try {
                    p?.inputStream?.bufferedReader()?.use { reader ->
                        val buf = CharArray(256)
                        while (true) {
                            val n = reader.read(buf)
                            if (n < 0) break
                            synchronized(collected) { collected.appendRange(buf, 0, n) }
                        }
                    }
                } catch (_: Throwable) {
                }
            }
            drain.isDaemon = true
            drain.start()
            val finished = p.waitFor(ROOT_TIMEOUT_SEC, TimeUnit.SECONDS)
            if (!finished) {
                try {
                    p.destroyForcibly()
                } catch (_: Throwable) {
                }
            }
            drain.join(200)
            val text = synchronized(collected) { collected.toString() }
            ProcessResult(
                finished = finished,
                exitCode = if (finished) runCatching { p.exitValue() }.getOrDefault(-1) else -1,
                output = text
            )
        } catch (e: Exception) {
            Log.e(TAG, "Process failed: ${cmd.joinToString(" ")}", e)
            ProcessResult(false, -1, "")
        } finally {
            try {
                p?.destroy()
            } catch (_: Throwable) {
            }
        }
    }

    fun checkRootFresh(): Boolean {
        val result = runProcess("su", "-c", "id")
        val available = result.finished && result.exitCode == 0 && result.output.contains("uid=0")
        isRootCached = available
        rootCacheAtMs = SystemClock.elapsedRealtime()
        return available
    }

    fun isRootAvailable(): Boolean {
        val cached = isRootCached
        if (cached != null && SystemClock.elapsedRealtime() - rootCacheAtMs < ROOT_CACHE_TTL_MS) {
            return cached
        }
        return checkRootFresh()
    }


    fun runRoot(cmd: String): Boolean {
        val result = runProcess("su", "-c", cmd)
        val ok = result.finished && result.exitCode == 0
        if (!ok) Log.w(TAG, "Root cmd exit=${result.exitCode} out=${result.output} cmd=$cmd")
        return ok
    }

    fun ensureBackgroundSurvival(packageName: String): Boolean {
        if (!isRootAvailable()) return false
        var ok = true
        ok = runRoot("cmd deviceidle whitelist +$packageName") && ok
        ok = runRoot("am set-standby-bucket $packageName active") && ok
        ok = runRoot("cmd appops set $packageName AUTO_START allow") && ok
        if (ok) Log.d(TAG, "Background survival protection applied")
        return ok
    }

    private var fastShell: Process? = null
    private var fastWriter: java.io.OutputStream? = null
    private val fastLock = Any()

    private val hwLock = Any()
    @Volatile
    private var drainAlive = false

    fun runRootFast(cmd: String): Boolean {
        synchronized(fastLock) {
            for (attempt in 0..1) {
                try {
                    var p = fastShell
                    var w = fastWriter
                    if (p == null || !p.isAlive || w == null) {
                        destroyFastShellLocked()
                        p = ProcessBuilder("su").redirectErrorStream(true).start()
                        fastShell = p
                        w = p.outputStream
                        fastWriter = w
                        drainAlive = true
                        val proc = p
                        Thread {
                            try {
                                proc.inputStream.bufferedReader().use { r ->
                                    while (drainAlive && r.readLine() != null) {
                                    }
                                }
                            } catch (_: Throwable) {
                            }
                        }.apply { isDaemon = true }.start()
                    }
                    w.write((cmd + "\n").toByteArray())
                    w.flush()
                    return true
                } catch (e: Exception) {
                    Log.w(TAG, "Fast root shell failed, recreating", e)
                    destroyFastShellLocked()
                }
            }
            return false
        }
    }

    private fun destroyFastShellLocked() {
        drainAlive = false
        try {
            fastWriter?.close()
        } catch (_: Throwable) {
        }
        try {
            fastShell?.destroy()
        } catch (_: Throwable) {
        }
        fastShell = null
        fastWriter = null
    }



    @Volatile
    private var hardwareInitialized = false

    fun ensureHardwareReady(): Boolean {
        var rootOk = true
        if (isRootAvailable()) {
            rootOk = resetHardwareToGlobal()
        }
        val shizukuOk = resetScene()
        return rootOk || shizukuOk
    }

    fun hardwareOff(): Boolean = synchronized(hwLock) {
        hardwareInitialized = false
        trackPushed(0xFF000000.toInt())
        runRoot(
            "SYS=\"$SYS_REG\"; " +
                    "echo 0 > \"$SYS_EFFECT\" && " +
                    "echo '0xa1 0x00' > \"\$SYS\" && " +
                    "echo '0xab 0x00' > \"\$SYS\""
        )
    }

    fun resetHardwareToGlobal(): Boolean {
        if (!isRootAvailable()) return false
        synchronized(hwLock) {
            val cmd = "SYS=\"$SYS_REG\"; " +
                    "echo 1 > \"$SYS_HWEN\" && " +
                    "echo 2 > \"$SYS_EFFECT\" && " +
                    "echo '0x00 0x11' > \"\$SYS\" && " +
                    "echo '0x6e 0xff' > \"\$SYS\" && " +
                    "echo '0x7a 0x00' > \"\$SYS\" && " +
                    "echo '0xa0 0x00' > \"\$SYS\" && " +
                    "echo '0xa1 0x00' > \"\$SYS\" && " +
                    "echo '0xa7 0xcc' > \"\$SYS\" && " +
                    "echo '0xab 0xff' > \"\$SYS\""
            val ok = runRoot(cmd)
            if (ok) hardwareInitialized = true
            return ok
        }
    }

    private fun sanitized(colorArgb: Int): Int =
        if (colorArgb == -1) 0xFFFEFEFE.toInt() else colorArgb

    @Volatile
    var lastHwColorA: Int = 0xFF000000.toInt()
        private set
    @Volatile
    var lastHwColorB: Int = 0xFF000000.toInt()
        private set

    private fun trackPushed(a: Int, b: Int = a) {
        lastHwColorA = a
        lastHwColorB = b
    }

    private fun hex(v: Int): String = String.format(java.util.Locale.ROOT, "0x%02x", v.coerceIn(0, 255))

    fun setHardwareColorDirect(colorArgb: Int): Boolean = synchronized(hwLock) {
        if (!hardwareInitialized) {
            resetHardwareToGlobal()
        }
        val c = sanitized(colorArgb)
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        trackPushed(c)
        val cmd = "SYS=\"$SYS_REG\"; " +
                "echo '0xa8 ${hex(r)}' > \"\$SYS\" && " +
                "echo '0xa9 ${hex(g)}' > \"\$SYS\" && " +
                "echo '0xaa ${hex(b)}' > \"\$SYS\" && " +
                "echo '0xab 0xff' > \"\$SYS\""
        runRootFast(cmd)
    }

    fun setHardwareBreath(r: Int, g: Int, b: Int): Boolean = synchronized(hwLock) {
        val cmd = "SYS=\"$SYS_REG\"; " +
                "echo '0xa1 0x00' > \"\$SYS\" && " +
                "echo '0x00 0x11' > \"\$SYS\" && " +
                "echo '0x6e 0xff' > \"\$SYS\" && " +
                "echo '0x7a 0x00' > \"\$SYS\" && " +
                "echo '0xa0 0x07' > \"\$SYS\" && " +
                "echo '0xa2 0x44' > \"\$SYS\" && " +
                "echo '0xa3 0x44' > \"\$SYS\" && " +
                "echo '0xa6 0x66' > \"\$SYS\" && " +
                "echo '0xa7 0x00' > \"\$SYS\" && " +
                "echo '0xa8 ${hex(r)}' > \"\$SYS\" && " +
                "echo '0xa9 ${hex(g)}' > \"\$SYS\" && " +
                "echo '0xaa ${hex(b)}' > \"\$SYS\" && " +
                "echo '0xab 0xff' > \"\$SYS\" && " +
                "echo '0xac 0x00' > \"\$SYS\" && " +
                "echo '0xa1 0x01' > \"\$SYS\""
        runRoot(cmd)
    }


    fun setDualSpotColors(colorA: Int, colorB: Int, full: Boolean = true): Boolean = synchronized(hwLock) {
        val a = sanitized(colorA)
        val bCol = sanitized(colorB)
        trackPushed(a, bCol)
        val rA = (a shr 16) and 0xFF
        val gA = (a shr 8) and 0xFF
        val bA = a and 0xFF

        val rB = (bCol shr 16) and 0xFF
        val gB = (bCol shr 8) and 0xFF
        val bB = bCol and 0xFF

        val init = if (full) {
            "echo 1 > \"$SYS_HWEN\" && " +
                    "echo 2 > \"$SYS_EFFECT\" && " +
                    "echo '0x00 0x11' > \"\$SYS\" && " +
                    "echo '0x6e 0xff' > \"\$SYS\" && " +
                    "echo '0x7a 0x01' > \"\$SYS\" && " +
                    "echo '0xa0 0x00' > \"\$SYS\" && " +
                    "echo '0xa1 0x00' > \"\$SYS\" && " +
                    "echo '0xab 0x00' > \"\$SYS\" && " +
                    "echo '0xac 0x10' > \"\$SYS\" && " +
                    "for b in 0x01 0x02 0x03 0x04 0x05 0x06 0x07 0x08 0x09 0x0a 0x0b 0x0c 0x0d 0x0e 0x0f 0x10 0x11 0x12 0x13 0x14 0x15 0x16 0x17 0x18; do echo \"\$b 0xff\" > \"\$SYS\"; done && "
        } else ""

        val cmd = "SYS=\"$SYS_REG\"; " + init +
                "echo '0x5c ${hex(rA)}' > \"\$SYS\" && " +
                "echo '0x5d ${hex(gA)}' > \"\$SYS\" && " +
                "echo '0x5e ${hex(bA)}' > \"\$SYS\" && " +
                "echo '0x53 ${hex(rB)}' > \"\$SYS\" && " +
                "echo '0x54 ${hex(gB)}' > \"\$SYS\" && " +
                "echo '0x55 ${hex(bB)}' > \"\$SYS\" && " +
                "echo '0x49 0x00' > \"\$SYS\""
        return if (full) runRoot(cmd) else runRootFast(cmd)
    }

    fun initSpinMode(): Boolean = synchronized(hwLock) {
        val cmd = "SYS=\"$SYS_REG\"; " +
                "echo 1 > \"$SYS_HWEN\" && " +
                "echo 2 > \"$SYS_EFFECT\" && " +
                "echo '0x00 0x11' > \"\$SYS\" && " +
                "echo '0x6e 0xff' > \"\$SYS\" && " +
                "echo '0x7a 0x00' > \"\$SYS\" && " +
                "echo '0xa0 0x00' > \"\$SYS\" && " +
                "echo '0xa1 0x00' > \"\$SYS\" && " +
                "echo '0xa7 0xcc' > \"\$SYS\" && " +
                "echo '0xab 0xff' > \"\$SYS\" && " +
                "echo '0xac 0x00' > \"\$SYS\""
        return runRoot(cmd)
    }

    fun setSpinFrame(isSpotA: Boolean, colorArgb: Int, brightness: Float = 1.0f): Boolean = synchronized(hwLock) {
        val bright = brightness.coerceIn(0f, 1f).let { if (it.isNaN()) 1f else it }
        val c = sanitized(colorArgb)
        trackPushed(c)
        val r = (((c shr 16) and 0xFF) * bright).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 0xFF) * bright).toInt().coerceIn(0, 255)
        val b = ((c and 0xFF) * bright).toInt().coerceIn(0, 255)
        val mask = if (isSpotA) "0x40" else "0x08"

        val cmd = "SYS=\"$SYS_REG\"; " +
                "echo '0xa8 ${hex(r)}' > \"\$SYS\" && " +
                "echo '0xa9 ${hex(g)}' > \"\$SYS\" && " +
                "echo '0xaa ${hex(b)}' > \"\$SYS\" && " +
                "echo '0xab $mask' > \"\$SYS\""
        return runRootFast(cmd)
    }


    fun setCameraRing(colorArgb: Int): Boolean {
        val finalColor = sanitized(colorArgb)
        if (isRootAvailable()) {
            return setHardwareColorDirect(finalColor)
        }
        val mgr = getLightsManager()
        if (mgr != null && hasShizukuPermission()) {
            return try {
                mgr.setColorCommon(finalColor, CAMERA_PKG, STYLE_MUSIC, 0)
                trackPushed(finalColor)
                true
            } catch (e: Exception) {
                Log.e(TAG, "setCameraRing via Shizuku failed", e)
                invalidateBinderCache()
                false
            }
        }
        return false
    }


    fun turnOff(): Boolean {
        var rootOk = false
        var shizukuOk = false
        trackPushed(0xFF000000.toInt())
        if (isRootAvailable()) {
            rootOk = hardwareOff()
        }
        try {
            val mgr = getLightsManager()
            if (mgr != null && hasShizukuPermission()) {
                mgr.setColorCommon(0, CAMERA_PKG, STYLE_MUSIC, 0)
                mgr.setColorCommon(0, CAMERA_PKG, STYLE_RESET, 0)
                mgr.setCustomLight(0, 0, 0, 0, 0, CAMERA_PKG, STYLE_RESET, 0)
                mgr.setColorfulLight(CAMERA_PKG, STYLE_RESET, 0)
                shizukuOk = true
            }
        } catch (e: Exception) {
            Log.e(TAG, "turnOff failed", e)
            invalidateBinderCache()
        }
        return rootOk || shizukuOk
    }

    fun setDualColorGame(): Boolean {
        return try {
            val mgr = getLightsManager() ?: return false
            mgr.setColorfulLight(CAMERA_PKG, 2, 0)
            true
        } catch (e: Exception) {
            Log.e(TAG, "setDualColorGame failed", e)
            invalidateBinderCache()
            false
        }
    }

    fun resetScene(): Boolean {
        return try {
            val mgr = getLightsManager() ?: return false

            mgr.setColorCommon(0, CAMERA_PKG, STYLE_RESET, 0)

            mgr.setCustomLight(0, 0, 0, 0, 0, CAMERA_PKG, 12, 0)

            mgr.setCustomLight(0, 0, 0, 0, 0, CAMERA_PKG, STYLE_RESET, 0)
            mgr.setColorfulLight(CAMERA_PKG, STYLE_RESET, 0)
            true
        } catch (e: Exception) {
            Log.e(TAG, "resetScene failed", e)
            invalidateBinderCache()
            false
        }
    }

    fun logColorLightSurface() {
        try {
            val clazz = Class.forName("android.app.ColorLightManager")
            val names = clazz.methods.joinToString(", ") { it.name }
            Log.w(TAG, "ColorLightManager methods: $names")
        } catch (e: Throwable) {
            Log.w(TAG, "ColorLightManager unavailable", e)
        }
    }
}
