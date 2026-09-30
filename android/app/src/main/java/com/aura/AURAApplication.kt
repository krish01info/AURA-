package com.aura

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.aura.update.UpdateCheckWorker
import dagger.hilt.android.HiltAndroidApp

/**
 * AURA Application class.
 *
 * Annotated with @HiltAndroidApp to trigger Hilt's code generation
 * and set up the dependency injection graph for the entire app.
 */
@HiltAndroidApp
class AURAApplication : Application() {

    companion object {
        const val NOTIFICATION_CHANNEL_ID     = "aura_agent_channel"
        const val NOTIFICATION_CHANNEL_UPDATE = "aura_update_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        // Background check every 12 hrs (keeps running even when app is closed)
        UpdateCheckWorker.scheduleUpdateChecks(this)
        // Immediate one-shot check on every launch so users see updates right away
        UpdateCheckWorker.checkNow(this)
    }

    /**
     * Creates persistent notification channels.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Agent foreground service channel (silent)
            val agentChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }

            // Update notification channel (high importance — user should see it)
            val updateChannel = NotificationChannel(
                NOTIFICATION_CHANNEL_UPDATE,
                "AURA Updates",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifies when a new AURA version is available on GitHub"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannels(listOf(agentChannel, updateChannel))
        }
    }
}
