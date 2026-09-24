package com.aura

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
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
        const val NOTIFICATION_CHANNEL_ID = "aura_agent_channel"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    /**
     * Creates the persistent notification channel for the AURA foreground service.
     * Required for Android 8.0+ (API 26+).
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW  // Silent — no sound/vibration
            ).apply {
                description = getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
}
