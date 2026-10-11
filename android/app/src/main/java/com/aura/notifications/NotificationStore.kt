package com.aura.notifications

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "NotificationStore"

/**
 * NotificationStore — recent notifications seen by [AuraNotificationListener], so AURA
 * can reply to a message, read messages aloud or tell what's playing **without
 * opening the app**.
 *
 * Privacy: kept in memory only (last [MAX_ENTRIES]), never written to disk or sent
 * anywhere. Cleared when the process ends.
 */
@Singleton
class NotificationStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Entry(
        val key: String,
        val pkg: String,
        val title: String,
        val text: String,
        val time: Long,
        /** The notification's own "Reply" action, if it has one. */
        val replyAction: Notification.Action?
    )

    private val entries = ArrayDeque<Entry>()

    /** True when the user has granted AURA notification access. */
    fun isEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    fun onPosted(sbn: StatusBarNotification) {
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return   // "3 new messages" summaries
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return   // music, downloads, calls
        val extras = n.extras ?: return
        val title = (extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()?.trim().orEmpty()
        val text = lastMessageText(extras) ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val reply = n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        synchronized(entries) {
            entries.removeAll { it.key == sbn.key }
            entries.addFirst(Entry(sbn.key, sbn.packageName, title, text, sbn.postTime, reply))
            while (entries.size > MAX_ENTRIES) entries.removeLast()
        }
    }

    fun onRemoved(sbn: StatusBarNotification) {
        synchronized(entries) { entries.removeAll { it.key == sbn.key } }
    }

    /** Latest notifications, newest first, optionally only from [pkg] and/or [person]. */
    fun recent(pkg: String?, person: String?, limit: Int = 5): List<Entry> = synchronized(entries) {
        entries.asSequence()
            .filter { pkg.isNullOrBlank() || it.pkg == pkg }
            .filter { person.isNullOrBlank() || it.title.contains(person, ignoreCase = true) }
            .take(limit)
            .toList()
    }

    /**
     * Reply to the latest notification from [person] (in [pkg], if given) using the
     * app's own Reply action. Returns null on success, or a user-readable problem.
     */
    fun reply(pkg: String?, person: String, message: String): String? {
        if (!isEnabled()) return NEEDS_ACCESS
        val entry = synchronized(entries) {
            entries.firstOrNull {
                (pkg.isNullOrBlank() || it.pkg == pkg) && it.replyAction != null &&
                    it.title.contains(person, ignoreCase = true)
            }
        } ?: return "I don't see a recent message from $person to reply to."
        val action = entry.replyAction ?: return "That notification can't be replied to."
        return try {
            val inputs = action.remoteInputs ?: return "That notification can't be replied to."
            val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, message) } }
            val intent = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(inputs, intent, results)
            action.actionIntent.send(context, 0, intent)
            Log.i(TAG, "Replied to ${entry.title} in ${entry.pkg}")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Reply failed: ${e.message}")
            "The reply couldn't be sent from the notification."
        }
    }

    /** "Song by Artist (Spotify)" for whatever is playing, or a reason it can't tell. */
    fun nowPlaying(): String {
        if (!isEnabled()) return NEEDS_ACCESS
        return try {
            val msm = context.getSystemService(MediaSessionManager::class.java)
            val sessions = msm.getActiveSessions(ComponentName(context, AuraNotificationListener::class.java))
            val playing = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                ?: sessions.firstOrNull()
                ?: return "Nothing is playing right now."
            val meta = playing.metadata
            val title = meta?.getString(MediaMetadata.METADATA_KEY_TITLE)
            val artist = meta?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            val app = try {
                context.packageManager.getApplicationLabel(
                    context.packageManager.getApplicationInfo(playing.packageName, 0)
                ).toString()
            } catch (_: Exception) { playing.packageName }
            when {
                title == null -> "Something is playing in $app."
                artist.isNullOrBlank() -> "$title, on $app."
                else -> "$title by $artist, on $app."
            }
        } catch (e: SecurityException) {
            NEEDS_ACCESS
        }
    }

    private fun lastMessageText(extras: Bundle): String? {
        @Suppress("DEPRECATION")
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES) ?: return null
        val last = messages.lastOrNull() as? Bundle ?: return null
        return last.getCharSequence("text")?.toString()?.trim()
    }

    companion object {
        const val MAX_ENTRIES = 50
        const val NEEDS_ACCESS =
            "I need notification access for that. Turn it on in AURA's Settings."
    }
}
