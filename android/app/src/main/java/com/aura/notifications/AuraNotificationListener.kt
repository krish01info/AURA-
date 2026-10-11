package com.aura.notifications

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * AuraNotificationListener — the system calls this for every notification once the
 * user grants "Notification access" (Settings → AURA → Notification access). It only
 * forwards to [NotificationStore]; the media-session lookup for "what's playing" also
 * needs this listener to be enabled.
 */
@AndroidEntryPoint
class AuraNotificationListener : NotificationListenerService() {

    @Inject lateinit var store: NotificationStore

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return // ignore AURA's own notifications
        store.onPosted(sbn)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        store.onRemoved(sbn)
    }

    override fun onListenerConnected() {
        // Pick up messages that arrived before the listener connected.
        try {
            activeNotifications?.forEach { if (it.packageName != packageName) store.onPosted(it) }
        } catch (_: Exception) { }
    }
}
