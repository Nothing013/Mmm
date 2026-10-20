package com.dashdazz.light

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

object UpdateChecker {

    private const val TAG = "DashDazzUpdate"
    private const val REPO = "olivia-hye/DashDazz"
    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"
    private const val ASSET_NAME = "DashDazz-release.apk"
    private const val DOWNLOAD_URL = "https://github.com/$REPO/releases/download"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_APK_BYTES = 64L * 1024 * 1024

    sealed interface UpdateStatus {
        object Idle : UpdateStatus
        object Checking : UpdateStatus
        object UpToDate : UpdateStatus
        data class Available(val tag: String, val versionCode: Int) : UpdateStatus
        data class Failed(val reason: String) : UpdateStatus
        data class Downloading(val tag: String, val percent: Int) : UpdateStatus
        data class NeedsInstallPermission(val tag: String) : UpdateStatus
    }

    internal fun encode(status: UpdateStatus): String = when (status) {
        is UpdateStatus.Idle -> "idle"
        is UpdateStatus.Checking -> "checking"
        is UpdateStatus.UpToDate -> "uptodate"
        is UpdateStatus.Available -> "avail:${status.tag}:${status.versionCode}"
        is UpdateStatus.Downloading -> "dl:${status.tag}:${status.percent}"
        is UpdateStatus.NeedsInstallPermission -> "needperm:${status.tag}"
        is UpdateStatus.Failed -> "failed:${status.reason}"
    }

    internal fun decode(encoded: String): UpdateStatus {
        val idx = encoded.indexOf(':')
        val head = if (idx < 0) encoded else encoded.substring(0, idx)
        val rest = if (idx < 0) "" else encoded.substring(idx + 1)
        return when (head) {
            "checking" -> UpdateStatus.Checking
            "uptodate" -> UpdateStatus.UpToDate
            "avail" -> {
                val parts = rest.split(":")
                UpdateStatus.Available(parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull() ?: 0)
            }
            "dl" -> {
                val parts = rest.split(":")
                UpdateStatus.Downloading(
                    parts.getOrElse(0) { "" }, parts.getOrNull(1)?.toIntOrNull() ?: 0
                )
            }
            "needperm" -> UpdateStatus.NeedsInstallPermission(rest)
            "failed" -> UpdateStatus.Failed(rest)
            else -> UpdateStatus.Idle
        }
    }

    fun canAutoCheck(context: Context): Boolean {
        val last = Prefs.getUpdateLastCheckMs(context)
        val never = last == 0L
        return never || System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    suspend fun check(context: Context): UpdateStatus = withContext(Dispatchers.IO) {
        try {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            var status: UpdateStatus
            try {
                conn.requestMethod = "GET"
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = READ_TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("User-Agent", "DashDazz/${BuildConfig.VERSION_NAME}")

                val code = conn.responseCode
                status = if (code != HttpURLConnection.HTTP_OK) {
                    val reason = when (code) {
                        403, 429 -> "rate_limited"
                        404 -> "no_release"
                        else -> "http_$code"
                    }
                    Log.d(TAG, "Update check returned HTTP $code")
                    UpdateStatus.Failed(reason)
                } else {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(body)
                    val tag = json.optString("tag_name")
                    val remote = versionCodeFromTag(tag)
                    val assetReady = json.optJSONArray("assets")
                        ?.let { arr ->
                            (0 until arr.length()).any {
                                arr.optJSONObject(it)?.optString("name") == ASSET_NAME
                            }
                        } ?: false
                    when {
                        remote == null -> UpdateStatus.Failed("bad_tag")
                        remote > BuildConfig.VERSION_CODE && assetReady ->
                            UpdateStatus.Available(tag, remote)
                        remote > BuildConfig.VERSION_CODE ->
                            UpdateStatus.Failed("asset_pending")
                        else -> UpdateStatus.UpToDate
                    }
                }
            } finally {
                conn.disconnect()
            }
            if (status !is UpdateStatus.Failed) {
                Prefs.setUpdateLastCheckMs(context, System.currentTimeMillis())
            }
            status
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.d(TAG, "Update check failed: ${e.javaClass.simpleName}: ${e.message}")
            UpdateStatus.Failed("network")
        }
    }

    internal fun versionCodeFromTag(tag: String): Int? {
        val cleaned = tag.trim().removePrefix("v")
        val parts = cleaned.split(".")
        if (parts.size < 2) return null
        return parts.last().trim().toIntOrNull()
    }

    private fun downloadTarget(context: Context, tag: String): File {
        val dir = File(context.cacheDir, "updates")
        if (!dir.exists()) dir.mkdirs()
        clearOldDownloads(dir)
        return File(dir, "DashDazz-${tag.removePrefix("v")}.apk")
    }

    private fun clearOldDownloads(dir: File) {
        val keep = dir.listFiles() ?: return
        for (f in keep) {
            if (f.isFile) f.delete()
        }
    }

    suspend fun download(
        context: Context,
        tag: String,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        val target = downloadTarget(context, tag)
        val temp = File(target.parentFile, target.name + ".part")
        try {
            val conn = URL("$DOWNLOAD_URL/$tag/$ASSET_NAME").openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = CONNECT_TIMEOUT_MS
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                    return@withContext Result.failure(IllegalStateException("download_http_${conn.responseCode}"))
                }
                val total = conn.contentLengthLong
                if (total <= 0 || total > MAX_APK_BYTES) {
                    return@withContext Result.failure(IllegalStateException("download_bad_size"))
                }

                var read = 0L
                var lastReported = -1
                conn.inputStream.use { input ->
                    temp.outputStream().use { out ->
                        val buf = ByteArray(16 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read > MAX_APK_BYTES) {
                                return@withContext Result.failure(IllegalStateException("download_too_large"))
                            }
                            val pct = ((read * 100) / total).toInt()
                            if (pct != lastReported) {
                                lastReported = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
                if (read != total) {
                    return@withContext Result.failure(IllegalStateException("download_truncated"))
                }
            } finally {
                conn.disconnect()
            }

            if (!isSignedBySameKey(context, temp)) {
                temp.delete()
                return@withContext Result.failure(IllegalStateException("signature_mismatch"))
            }

            if (target.exists()) target.delete()
            if (!temp.renameTo(target)) {
                temp.delete()
                return@withContext Result.failure(IllegalStateException("rename_failed"))
            }
            onProgress(100)
            Result.success(target)
        } catch (e: CancellationException) {
            temp.delete()
            throw e
        } catch (e: Throwable) {
            temp.delete()
            Log.d(TAG, "Download failed: ${e.javaClass.simpleName}: ${e.message}")
            Result.failure(e)
        }
    }

    private fun isSignedBySameKey(context: Context, archive: File): Boolean {
        val installedInfo = runCatching {
            context.packageManager
                .getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull() ?: return false
        val archiveInfo = runCatching {
            context.packageManager.getPackageArchiveInfo(
                archive.absolutePath, PackageManager.GET_SIGNING_CERTIFICATES
            )
        }.getOrNull() ?: return false
        if (archiveInfo.packageName != context.packageName) {
            Log.w(TAG, "Downloaded APK is for ${archiveInfo.packageName}, not ${context.packageName}")
            return false
        }
        val installed = currentSignerDigests(installedInfo.signingInfo) ?: return false
        val downloaded = currentSignerDigests(archiveInfo.signingInfo) ?: return false
        return installed.isNotEmpty() && installed == downloaded
    }

    private fun currentSignerDigests(signingInfo: android.content.pm.SigningInfo?): Set<String>? {
        if (signingInfo == null) return null
        val signers = signingInfo.apkContentsSigners ?: return null
        return runCatching {
            signers.map { cert ->
                MessageDigest.getInstance("SHA-256").digest(cert.toByteArray())
                    .joinToString("") { b -> "%02x".format(b) }
            }.toSet()
        }.getOrNull()
    }

    fun canInstallPackages(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context, "${context.packageName}.updates", apk
        )
        return Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    fun installPermissionIntent(context: Context): Intent {
        return Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
            data = Uri.parse("package:${context.packageName}")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
}