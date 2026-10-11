package com.aura.skills

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private const val TAG = "AppShortcutsUpdater"
private const val WORK_NAME = "aura_app_shortcuts_update"

/**
 * AppShortcutsUpdater — once a day, downloads `app_shortcuts.json` from the repo's
 * `main` branch. If it parses and its `version` is higher than what the phone has,
 * it is saved to `filesDir` and [SkillSeeder] re-syncs the built-in skills.
 *
 * So fixing or adding an app shortcut = edit the JSON, bump `version`, merge to main.
 * A broken file is ignored (the bundled one keeps working).
 */
@HiltWorker
class AppShortcutsUpdater @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted params: WorkerParameters,
    private val okHttpClient: OkHttpClient,
    private val skillSeeder: SkillSeeder
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val body = okHttpClient.newCall(Request.Builder().url(URL).build()).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext Result.retry()
                resp.body?.string() ?: return@withContext Result.retry()
            }
            val remote = SkillSeeder.parse(body)   // throws on a broken file
            if (remote.skills.isEmpty()) return@withContext Result.success()

            val local = SkillSeeder.downloadedFile(context)
            val localVersion = try {
                if (local.exists()) SkillSeeder.parse(local.readText()).version else 0
            } catch (_: Exception) { 0 }
            val bundledVersion = try {
                context.assets.open(SkillSeeder.ASSET_NAME).bufferedReader().use { SkillSeeder.parse(it.readText()).version }
            } catch (_: Exception) { 0 }

            if (remote.version > maxOf(localVersion, bundledVersion)) {
                val tmp = java.io.File(local.parentFile, local.name + ".tmp")
                tmp.writeText(body)
                tmp.renameTo(local)
                skillSeeder.seedIfEmpty()
                Log.i(TAG, "App shortcuts updated to v${remote.version} (${remote.skills.size} skills)")
            }
            Result.success()
        } catch (e: java.io.IOException) {
            Result.retry()
        } catch (e: Exception) {
            Log.w(TAG, "Remote app_shortcuts.json ignored: ${e.message}")
            Result.success()
        }
    }

    companion object {
        const val URL =
            "https://raw.githubusercontent.com/krish01info/AURA-/main/android/app/src/main/assets/app_shortcuts.json"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<AppShortcutsUpdater>(1, TimeUnit.DAYS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
