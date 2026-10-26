package com.andrerinas.openheadunit.ota

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.andrerinas.openheadunit.App
import com.andrerinas.openheadunit.BuildConfig
import com.andrerinas.openheadunit.R
import com.andrerinas.openheadunit.utils.AppLog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object OtaUpdateManager {

    const val GITHUB_OWNER = "wsknnew"
    const val GITHUB_REPO = "DiAutoNew"
    const val GITHUB_API_LATEST_RELEASE = "https://api.github.com/repos/$GITHUB_OWNER/$GITHUB_REPO/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val versionName: String,
        val title: String,
        val changelog: String,
        val apkUrl: String,
        val apkSize: Long,
        val publishedAt: String
    )

    sealed class CheckResult {
        data class UpdateAvailable(val release: ReleaseInfo, val currentVersion: String) : CheckResult()
        data class UpToDate(val currentVersion: String) : CheckResult()
        data class NoReleasesFound(val message: String) : CheckResult()
        data class Error(val error: Throwable) : CheckResult()
    }

    suspend fun checkForUpdate(): CheckResult = withContext(Dispatchers.IO) {
        try {
            val url = URL(GITHUB_API_LATEST_RELEASE)
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "DiAuto-Headunit/${BuildConfig.VERSION_NAME}")
                setRequestProperty("Accept", "application/vnd.github.v3+json")
            }

            val responseCode = connection.responseCode
            if (responseCode == 404) {
                return@withContext CheckResult.NoReleasesFound(
                    "No releases published on $GITHUB_OWNER/$GITHUB_REPO yet."
                )
            }
            if (responseCode !in 200..299) {
                return@withContext CheckResult.Error(
                    IllegalStateException("GitHub API returned HTTP $responseCode: ${connection.responseMessage}")
                )
            }

            val jsonStr = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(jsonStr)

            val tagName = json.optString("tag_name", "").trim()
            val title = json.optString("name", tagName)
            val changelog = json.optString("body", "No changelog provided.")
            val publishedAt = json.optString("published_at", "")

            // Find APK asset (prefer matching package name)
            val assets = json.optJSONArray("assets")
            var apkUrl: String? = null
            var apkSize = 0L

            if (assets != null) {
                var preferredUrl: String? = null
                var preferredSize = 0L
                var fallbackUrl: String? = null
                var fallbackSize = 0L
                val currentPkg = try { App.instance?.packageName ?: "com.wsknnew.diautonew" } catch (e: Exception) { "com.wsknnew.diautonew" }

                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        val downloadUrl = asset.optString("browser_download_url", "")
                        val size = asset.optLong("size", 0L)
                        if (name.contains(currentPkg, ignoreCase = true) || name.contains("diautonew", ignoreCase = true)) {
                            preferredUrl = downloadUrl
                            preferredSize = size
                            break
                        }
                        if (fallbackUrl == null) {
                            fallbackUrl = downloadUrl
                            fallbackSize = size
                        }
                    }
                }
                apkUrl = preferredUrl ?: fallbackUrl
                apkSize = if (preferredUrl != null) preferredSize else fallbackSize
            }

            if (apkUrl.isNullOrBlank()) {
                return@withContext CheckResult.NoReleasesFound(
                    "Release $tagName has no .apk asset attached."
                )
            }

            val remoteVersion = tagName.removePrefix("v").removePrefix("V").trim()
            val currentVersion = BuildConfig.VERSION_NAME.removePrefix("v").removePrefix("V").trim()

            val releaseInfo = ReleaseInfo(
                tagName = tagName,
                versionName = remoteVersion,
                title = title,
                changelog = changelog,
                apkUrl = apkUrl,
                apkSize = apkSize,
                publishedAt = publishedAt
            )

            if (isNewerVersion(remoteVersion, currentVersion)) {
                CheckResult.UpdateAvailable(releaseInfo, BuildConfig.VERSION_NAME)
            } else {
                CheckResult.UpToDate(BuildConfig.VERSION_NAME)
            }
        } catch (e: Exception) {
            AppLog.e("OtaUpdateManager: Failed to check for update", e)
            CheckResult.Error(e)
        }
    }

    /**
     * Compare semantic version numbers (e.g. 0.3.13 > 0.3.12).
     */
    fun isNewerVersion(remote: String, current: String): Boolean {
        try {
            val remoteClean = remote.split("-")[0]
            val currentClean = current.split("-")[0]

            val remoteParts = remoteClean.split(".").mapNotNull { it.toIntOrNull() }
            val currentParts = currentClean.split(".").mapNotNull { it.toIntOrNull() }

            val maxLen = maxOf(remoteParts.size, currentParts.size)
            for (i in 0 until maxLen) {
                val r = remoteParts.getOrElse(i) { 0 }
                val c = currentParts.getOrElse(i) { 0 }
                if (r > c) return true
                if (r < c) return false
            }
        } catch (_: Exception) {}
        return false
    }

    suspend fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, percent: Int) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val destFile = File(context.cacheDir, "diauto_update.apk")
        if (destFile.exists()) destFile.delete()

        var currentUrl = apkUrl
        var redirects = 0
        var connection: HttpURLConnection? = null

        // Handle up to 5 redirects (GitHub releases redirect to AWS S3 objects.githubusercontent.com)
        while (redirects < 5) {
            val url = URL(currentUrl)
            connection = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "DiAuto-Headunit/${BuildConfig.VERSION_NAME}")
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val newLocation = connection.getHeaderField("Location")
                connection.disconnect()
                if (newLocation != null) {
                    currentUrl = newLocation
                    redirects++
                    continue
                }
            }
            break
        }

        val conn = connection ?: throw IllegalStateException("Could not connect to download URL")
        if (conn.responseCode !in 200..299) {
            throw IllegalStateException("Download failed with HTTP ${conn.responseCode}: ${conn.responseMessage}")
        }

        val totalBytes = conn.contentLength.toLong()
        var downloadedBytes = 0L

        conn.inputStream.use { input ->
            FileOutputStream(destFile).use { output ->
                val buffer = ByteArray(16 * 1024)
                var bytesRead: Int
                var lastProgressUpdate = 0L

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloadedBytes += bytesRead

                    val now = System.currentTimeMillis()
                    if (now - lastProgressUpdate > 100 || downloadedBytes == totalBytes) {
                        lastProgressUpdate = now
                        val percent = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt() else 0
                        withContext(Dispatchers.Main) {
                            onProgress(downloadedBytes, totalBytes, percent)
                        }
                    }
                }
            }
        }
        destFile
    }

    /**
     * Install downloaded APK:
     * 1. Try silent root install if root is available
     * 2. Fallback to standard system package installer via FileProvider
     */
    fun installApk(context: Context, apkFile: File): Boolean {
        if (!apkFile.exists()) return false

        // 1. Silent root install attempt
        try {
            val suExecutor = App.provide(context).suExecutor
            if (suExecutor.checkPermission()) {
                AppLog.i("OtaUpdateManager: Root available, attempting silent installation via pm install -r")
                val exitCode = suExecutor.runShell("pm install -r \"${apkFile.absolutePath}\"")
                if (exitCode == 0) {
                    AppLog.i("OtaUpdateManager: Silent root install succeeded!")
                    return true
                }
                AppLog.w("OtaUpdateManager: Silent root install exited with $exitCode, falling back to PackageInstaller")
            }
        } catch (e: Exception) {
            AppLog.w("OtaUpdateManager: Silent install exception: ${e.message}")
        }

        // 2. Standard system PackageInstaller prompt via FileProvider
        return try {
            val authority = "${context.packageName}.fileprovider"
            val apkUri: Uri = FileProvider.getUriForFile(context, authority, apkFile)

            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            AppLog.e("OtaUpdateManager: Failed to launch PackageInstaller", e)
            false
        }
    }

    /**
     * UI helper: Checks for updates and displays interactive dialogs on the given activity.
     */
    fun checkWithUi(activity: Activity, silentIfUpToDate: Boolean = false) {
        val scope = CoroutineScope(Dispatchers.Main)
        if (!silentIfUpToDate) {
            Toast.makeText(activity, activity.getString(R.string.checking_for_updates), Toast.LENGTH_SHORT).show()
        }

        scope.launch {
            when (val result = checkForUpdate()) {
                is CheckResult.UpdateAvailable -> {
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        showUpdateDialog(activity, result.release)
                    }
                }
                is CheckResult.UpToDate -> {
                    if (!silentIfUpToDate && !activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity,
                            activity.getString(R.string.update_up_to_date, result.currentVersion),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
                is CheckResult.NoReleasesFound -> {
                    if (!silentIfUpToDate && !activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(activity, result.message, Toast.LENGTH_LONG).show()
                    }
                }
                is CheckResult.Error -> {
                    if (!silentIfUpToDate && !activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity,
                            "${activity.getString(R.string.update_failed)}\n${result.error.localizedMessage ?: ""}",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    fun showUpdateDialog(activity: Activity, release: ReleaseInfo) {
        val sizeMb = if (release.apkSize > 0) String.format("%.1f MB", release.apkSize / (1024f * 1024f)) else ""
        val versionText = "Version ${release.tagName} $sizeMb\n(Current: ${BuildConfig.VERSION_NAME})"

        val message = StringBuilder()
        message.append(versionText).append("\n\n")
        message.append(release.changelog.trim())

        MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setTitle(activity.getString(R.string.update_available_title))
            .setMessage(message.toString())
            .setPositiveButton(activity.getString(R.string.update_download_install)) { _, _ ->
                startDownloadWithProgress(activity, release)
            }
            .setNegativeButton(activity.getString(R.string.later), null)
            .show()
    }

    private fun startDownloadWithProgress(activity: Activity, release: ReleaseInfo) {
        // Build download progress dialog
        val progressDialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_ota_progress, null, false)
        val progressBar = progressDialogView.findViewById<ProgressBar>(R.id.ota_progress_bar)
        val statusText = progressDialogView.findViewById<TextView>(R.id.ota_progress_status)
        val titleText = progressDialogView.findViewById<TextView>(R.id.ota_progress_title)

        titleText.text = "Downloading DiAuto ${release.tagName}"
        statusText.text = "Connecting to GitHub…"
        progressBar.isIndeterminate = true

        var downloadJob: Job? = null

        val dialog = MaterialAlertDialogBuilder(activity, R.style.DarkAlertDialog)
            .setView(progressDialogView)
            .setCancelable(false)
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                downloadJob?.cancel()
            }
            .create()

        dialog.show()

        downloadJob = CoroutineScope(Dispatchers.Main).launch {
            try {
                val apkFile = downloadApk(activity, release.apkUrl) { bytes, total, percent ->
                    progressBar.isIndeterminate = false
                    progressBar.progress = percent
                    val mbDownloaded = bytes / (1024f * 1024f)
                    val mbTotal = total / (1024f * 1024f)
                    statusText.text = String.format("%.1f / %.1f MB (%d%%)", mbDownloaded, mbTotal, percent)
                }

                statusText.text = activity.getString(R.string.update_installing)
                dialog.dismiss()

                installApk(activity, apkFile)
            } catch (e: Exception) {
                if (dialog.isShowing) dialog.dismiss()
                AppLog.e("OtaUpdateManager: Download failed", e)
                Toast.makeText(activity, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
