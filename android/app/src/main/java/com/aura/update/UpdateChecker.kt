package com.aura.update

import android.util.Log
import com.aura.BuildConfig
import com.aura.data.model.GitHubRelease
import com.aura.data.model.UpdateInfo
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "UpdateChecker"

private interface GitHubApiService {
    @GET("repos/{owner}/{repo}/releases/latest")
    suspend fun getLatestRelease(
        @Path("owner") owner: String,
        @Path("repo")  repo: String
    ): GitHubRelease
}

/**
 * UpdateChecker — polls GitHub Releases API for the latest AURA APK.
 *
 * Uses a dedicated OkHttpClient (no auth interceptor) so it's completely
 * independent of the Groq LLM client.
 *
 * GitHub free API allows 60 unauthenticated requests/hour — more than enough
 * for a once-per-12-hours update check.
 */
@Singleton
class UpdateChecker @Inject constructor() {

    companion object {
        const val GITHUB_OWNER = "krish01info"
        const val GITHUB_REPO  = "AURA-"
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val api: GitHubApiService by lazy {
        val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(
                    chain.request().newBuilder()
                        .header("Accept", "application/vnd.github.v3+json")
                        .header("User-Agent", "AURA-Android/${BuildConfig.VERSION_NAME}")
                        .build()
                )
            }
            .build()

        Retrofit.Builder()
            .baseUrl("https://api.github.com/")
            .client(client)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()
            .create(GitHubApiService::class.java)
    }

    /**
     * Fetch the latest GitHub release and compare it with the installed version.
     *
     * @return [UpdateInfo] with [UpdateInfo.isUpdateAvailable] = true if an update exists,
     *         or null if the network call fails.
     */
    suspend fun checkForUpdate(): UpdateInfo? {
        return try {
            val release = api.getLatestRelease(GITHUB_OWNER, GITHUB_REPO)
            val latestVersion  = release.tagName.trimStart('v', 'V')
            val currentVersion = BuildConfig.VERSION_NAME

            // Find the APK asset in the release (looks for *.apk)
            val apkAsset = release.assets.firstOrNull {
                it.name.endsWith(".apk", ignoreCase = true)
            }

            val isUpdateAvailable = isNewer(latestVersion, currentVersion)

            Log.i(TAG, "Current: $currentVersion | Latest: $latestVersion | Update: $isUpdateAvailable")

            UpdateInfo(
                latestVersion    = latestVersion,
                currentVersion   = currentVersion,
                isUpdateAvailable = isUpdateAvailable,
                releaseNotes     = release.body,
                apkDownloadUrl   = apkAsset?.downloadUrl,
                releasePageUrl   = release.htmlUrl,
                apkSizeBytes     = apkAsset?.size ?: 0L
            )
        } catch (e: Exception) {
            Log.e(TAG, "Update check failed: ${e.message}")
            null
        }
    }

    /**
     * Compares semantic version strings (major.minor.patch).
     * Returns true if [latest] is strictly newer than [current].
     */
    private fun isNewer(latest: String, current: String): Boolean {
        return try {
            val l = latest.split(".").map { it.toIntOrNull() ?: 0 }
            val c = current.split(".").map { it.toIntOrNull() ?: 0 }
            val maxLen = maxOf(l.size, c.size)
            for (i in 0 until maxLen) {
                val lv = l.getOrElse(i) { 0 }
                val cv = c.getOrElse(i) { 0 }
                if (lv > cv) return true
                if (lv < cv) return false
            }
            false // identical versions
        } catch (e: Exception) {
            false
        }
    }
}
