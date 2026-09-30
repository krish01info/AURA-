package com.aura.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * GitHub Release API response — mapped from:
 * https://api.github.com/repos/krish01info/AURA-/releases/latest
 */
@Serializable
data class GitHubRelease(
    @SerialName("tag_name")    val tagName: String,          // e.g. "v0.2.0"
    @SerialName("name")        val name: String,             // e.g. "AURA v0.2.0"
    @SerialName("body")        val body: String = "",        // release notes markdown
    @SerialName("html_url")    val htmlUrl: String,          // GitHub release page URL
    @SerialName("published_at") val publishedAt: String,     // ISO-8601 timestamp
    @SerialName("assets")      val assets: List<GitHubAsset> = emptyList()
)

@Serializable
data class GitHubAsset(
    @SerialName("name")                  val name: String,           // filename, e.g. "aura-v0.2.0.apk"
    @SerialName("browser_download_url") val downloadUrl: String,    // direct APK download URL
    @SerialName("size")                  val size: Long,             // bytes
    @SerialName("content_type")          val contentType: String = ""
)

/**
 * Parsed, app-ready update info.
 */
data class UpdateInfo(
    val latestVersion: String,        // e.g. "0.2.0"  (tag stripped of leading "v")
    val currentVersion: String,       // from BuildConfig.VERSION_NAME
    val isUpdateAvailable: Boolean,
    val releaseNotes: String,
    val apkDownloadUrl: String?,      // null if no APK asset in release
    val releasePageUrl: String,
    val apkSizeBytes: Long
)
