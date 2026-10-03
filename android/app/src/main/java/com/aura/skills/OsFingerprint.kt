package com.aura.skills

import android.os.Build
import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "OsFingerprint"

/**
 * OsFingerprint — detects the device's OS class, skin, and API level.
 *
 * Used by:
 *  - SkillLearner  → tags auto-learned skills with OS info
 *  - SkillRouter   → bonus scoring for OS-matched skills
 *  - SkillSyncer   → filters cloud downloads to compatible skills only
 *  - OsMigrationHandler → detects Android upgrades
 */
@Singleton
class OsFingerprint @Inject constructor() {

    val apiLevel: Int = Build.VERSION.SDK_INT
    val osVersion: String = Build.VERSION.RELEASE
    val deviceModel: String = Build.MODEL
    val manufacturer: String = Build.MANUFACTURER
    val brand: String = Build.BRAND

    val osSkin: String = detectSkin()
    val skinVersion: String? = detectSkinVersion()

    /** Broad OS class tag used for skill filtering and storage. */
    val osClassTag: String = when (apiLevel) {
        29       -> "android_10"
        30       -> "android_11"
        in 31..33 -> "android_12_13"
        in 34..99 -> "android_14_plus"
        else     -> "universal"
    }

    override fun toString(): String =
        "Android $osVersion (API $apiLevel) | $deviceModel | $osSkin ${skinVersion ?: ""}"

    private fun detectSkin(): String = when {
        manufacturer.equals("samsung",  ignoreCase = true) -> "oneui"
        manufacturer.equals("xiaomi",   ignoreCase = true) -> "miui"
        manufacturer.equals("oppo",     ignoreCase = true) -> "coloros"
        manufacturer.equals("realme",   ignoreCase = true) -> "coloros"
        manufacturer.equals("oneplus",  ignoreCase = true) -> "oxygenos"
        manufacturer.equals("google",   ignoreCase = true) -> "stock"
        manufacturer.equals("nothing",  ignoreCase = true) -> "nothing_os"
        manufacturer.equals("motorola", ignoreCase = true) -> "stock"
        else -> "stock"
    }

    private fun detectSkinVersion(): String? {
        val props = listOf(
            "ro.build.version.oneui",      // Samsung OneUI
            "ro.miui.ui.version.name",     // Xiaomi MIUI
            "ro.build.version.opporom",    // Oppo ColorOS
            "ro.oxygen.version"            // OnePlus OxygenOS
        )
        return props.firstNotNullOfOrNull { prop ->
            try {
                val process = Runtime.getRuntime().exec(arrayOf("getprop", prop))
                val result = process.inputStream.bufferedReader().readLine()?.trim()
                if (!result.isNullOrBlank()) {
                    Log.d(TAG, "Skin version from $prop: $result")
                    result
                } else null
            } catch (_: Exception) { null }
        }
    }

    companion object {
        /** Convert an API level to its osClassTag. */
        fun osClassTagForApi(apiLevel: Int): String = when (apiLevel) {
            29       -> "android_10"
            30       -> "android_11"
            in 31..33 -> "android_12_13"
            in 34..99 -> "android_14_plus"
            else     -> "universal"
        }
    }
}
