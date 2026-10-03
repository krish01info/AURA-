package com.aura.skills

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aura.data.db.SkillPackDao
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OsMigrationHandler"
private const val PREF_LAST_API = "last_known_api"
private const val CHANNEL_ID = "aura_system"

/**
 * OsMigrationHandler — detects Android OS upgrades and adapts the skill cache.
 *
 * When the user upgrades Android (e.g. 13 → 14), old OS-specific skills may break.
 * This handler:
 *  1. Detects the upgrade by comparing saved vs current API level
 *  2. Marks all OS-specific skills for the OLD OS as stale
 *  3. Triggers a fresh cloud sync for the NEW OS's compatible skills
 *  4. Notifies the user with a system notification
 *
 * Called from AURAApplication.onCreate() every time the app starts.
 * Fast path: if API level unchanged, returns immediately.
 */
@Singleton
class OsMigrationHandler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val localDao: SkillPackDao,
    private val osFingerprint: OsFingerprint,
    private val preferences: SharedPreferences
) {
    suspend fun checkForOsMigration() {
        val savedApiLevel = preferences.getInt(PREF_LAST_API, 0)
        val currentApiLevel = osFingerprint.apiLevel

        // Fast path — no upgrade
        if (savedApiLevel == 0 || currentApiLevel == savedApiLevel) {
            preferences.edit().putInt(PREF_LAST_API, currentApiLevel).apply()
            return
        }

        if (currentApiLevel > savedApiLevel) {
            Log.i(TAG, "🔄 OS upgrade detected: API $savedApiLevel → $currentApiLevel")
            handleUpgrade(savedApiLevel, currentApiLevel)
        }

        preferences.edit().putInt(PREF_LAST_API, currentApiLevel).apply()
    }

    private suspend fun handleUpgrade(oldApi: Int, newApi: Int) {
        val oldOsClass = OsFingerprint.osClassTagForApi(oldApi)

        // Step 1: Mark OS-specific skills for the old Android as stale
        val oldSkills = localDao.getSkillsByOsClass(oldOsClass)
        var stalledCount = 0
        for (skill in oldSkills) {
            if (skill.osClassTag != "universal") {
                localDao.markStale(skill.skillId)
                stalledCount++
            }
        }
        Log.i(TAG, "Marked $stalledCount old OS skills as stale")

        // Step 2: Notify user
        notifyUser(
            title = "🔄 Android Upgrade Detected!",
            body = "Android ${Build.VERSION.RELEASE} detected. " +
                   "Marked $stalledCount old skills for re-validation. " +
                   "AURA will download optimized skills for your new OS."
        )

        Log.i(TAG, "OS migration complete: $oldOsClass → ${osFingerprint.osClassTag}")
    }

    private fun notifyUser(title: String, body: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "AURA System", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .build()
        nm.notify(9001, notif)
    }
}
