package com.aura.update

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateManager"

/**
 * UpdateManager — downloads the latest APK from GitHub and triggers the system installer.
 *
 * Flow:
 *   1. [downloadAndInstall] enqueues a DownloadManager job → shows system download notification
 *   2. On completion, [installApk] is called automatically via a BroadcastReceiver
 *   3. Android's package installer opens — user taps Install
 *
 * Requires:
 *   - android.permission.REQUEST_INSTALL_PACKAGES in manifest
 *   - FileProvider entry in manifest (authority = "${packageName}.fileprovider")
 *   - res/xml/file_provider_paths.xml with Downloads directory declared
 */
@Singleton
class UpdateManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    private var activeDownloadId: Long = -1L

    /**
     * Start downloading the APK and register to auto-install on completion.
     *
     * @param downloadUrl  Direct URL to the .apk file on GitHub
     * @param version      Version string used for the filename, e.g. "0.2.0"
     */
    fun downloadAndInstall(downloadUrl: String, version: String) {
        val fileName = "aura-v$version.apk"

        // Cancel any previous in-progress download
        if (activeDownloadId != -1L) {
            downloadManager.remove(activeDownloadId)
        }

        val request = DownloadManager.Request(Uri.parse(downloadUrl))
            .setTitle("AURA Update v$version")
            .setDescription("Downloading AURA update…")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            .setAllowedOverMetered(true)
            .setAllowedOverRoaming(false)

        activeDownloadId = downloadManager.enqueue(request)
        Log.i(TAG, "APK download started. downloadId=$activeDownloadId, file=$fileName")

        // Register receiver to install once download completes
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val completedId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                if (completedId == activeDownloadId) {
                    Log.i(TAG, "Download complete — triggering installer")
                    context.unregisterReceiver(this)
                    installApk(fileName)
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(
                receiver,
                IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
            )
        }
    }

    /**
     * Trigger the Android package installer for the downloaded APK.
     * Uses FileProvider to safely share the file URI with the installer.
     */
    private fun installApk(fileName: String) {
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            fileName
        )

        if (!file.exists()) {
            Log.e(TAG, "APK file not found: ${file.absolutePath}")
            return
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        context.startActivity(installIntent)
    }
}
