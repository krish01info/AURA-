package com.aura.update

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.aura.AURAApplication
import com.aura.data.model.UpdateInfo
import com.aura.ui.MainActivity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

private const val TAG = "UpdateCheckWorker"
private const val WORK_NAME   = "aura_update_check"
private const val NOTIF_ID    = 2001
private const val CHANNEL_ID  = AURAApplication.NOTIFICATION_CHANNEL_UPDATE

/**
 * UpdateCheckWorker — runs in the background every 12 hours.
 *
 * On each execution:
 *   1. Calls [UpdateChecker.checkForUpdate]
 *   2. If a new version is found → shows a persistent notification with "Download & Install" action
 *   3. If no update → succeeds silently
 *
 * Scheduled from [AURAApplication.onCreate] via [scheduleUpdateChecks].
 */
@HiltWorker
class UpdateCheckWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val updateChecker: UpdateChecker,
    private val updateManager: UpdateManager
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "Running update check…")
        val info = updateChecker.checkForUpdate() ?: return Result.retry()

        if (info.isUpdateAvailable) {
            Log.i(TAG, "Update available: ${info.latestVersion}")
            showUpdateNotification(info)
        } else {
            Log.i(TAG, "App is up to date (${info.currentVersion})")
        }

        return Result.success()
    }

    // ──────────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────────

    private fun showUpdateNotification(info: UpdateInfo) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // "Open App" intent — opens MainActivity to show the update banner
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            putExtra(EXTRA_SHOW_UPDATE, true)
        }
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sizeStr = if (info.apkSizeBytes > 0) {
            " (${info.apkSizeBytes / 1_048_576} MB)"
        } else ""

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("🚀 AURA ${info.latestVersion} is available!")
            .setContentText("Tap to download and install the update$sizeStr")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        buildString {
                            appendLine("Version ${info.latestVersion} is ready to install$sizeStr.")
                            if (info.releaseNotes.isNotBlank()) {
                                appendLine()
                                appendLine(info.releaseNotes.take(200))
                            }
                        }
                    )
            )
            .setContentIntent(openPending)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(
                android.R.drawable.stat_sys_download,
                "Install Update",
                openPending
            )
            .build()

        nm.notify(NOTIF_ID, notif)
    }

    companion object {
        const val EXTRA_SHOW_UPDATE = "show_update_banner"

        /**
         * Schedule a periodic update check every 12 hours.
         * Safe to call multiple times — KEEP policy ensures it only schedules once.
         */
        fun scheduleUpdateChecks(context: Context) {
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(
                repeatInterval = 12,
                repeatIntervalTimeUnit = TimeUnit.HOURS
            ).build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,  // don't restart if already scheduled
                request
            )

            Log.i(TAG, "Update check scheduled every 12 hours")
        }

        /**
         * Run an immediate one-shot update check (e.g. user taps "Check for updates").
         */
        fun checkNow(context: Context) {
            val request = androidx.work.OneTimeWorkRequestBuilder<UpdateCheckWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
