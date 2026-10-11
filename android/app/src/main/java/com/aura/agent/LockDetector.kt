package com.aura.agent

/**
 * LockDetector — does this screen text look like an app lock or the phone's lock screen?
 * ("Enter PIN", "Use fingerprint", "Draw pattern", "WhatsApp locked"…)
 *
 * Only consulted after a step has FAILED, so a rare false positive just means AURA
 * waits (with a Stop button) instead of failing. Pure Kotlin — unit-tested.
 */
object LockDetector {

    /** Packages that only come to the front as an app lock (MIUI, ColorOS/realme/OnePlus). */
    val LOCK_PACKAGES = setOf(
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.oplus.safecenter",
        "com.coloros.securepay",
        "com.huawei.systemmanager"
    )

    private val PHRASES = listOf(
        "enter pin", "enter your pin", "enter password", "enter your password", "enter passcode",
        "enter app lock", "draw pattern", "draw your pattern", "draw unlock pattern", "confirm your pattern",
        "use fingerprint", "use your fingerprint", "touch the fingerprint", "fingerprint sensor",
        "scan your fingerprint", "verify it's you", "verify your identity", "confirm it's you",
        "use face unlock", "use screen lock", "app lock", "applock", "app locked", "tap to unlock",
        "unlock to continue", "unlock with", "forgot pin", "forgot pattern"
    )

    /** "WhatsApp locked", "Unlock WhatsApp", "Paytm is locked". */
    private val LOCKED_APP = Regex("(^|\\n)\\s*(unlock \\S+|\\S+( is)? locked)\\s*($|\\n)")

    fun looksLocked(screenText: String): Boolean {
        val t = screenText.lowercase().replace('’', '\'')
        return PHRASES.any { it in t } || LOCKED_APP.containsMatchIn(t)
    }
}
