package com.aura.skills

import java.security.MessageDigest

/**
 * SkillIdentity — generates deterministic canonical skill IDs.
 *
 * The canonical ID is derived from (appPackage + intentCategory + intentAction)
 * so the SAME skill always maps to the SAME ID regardless of who creates it.
 * This makes deduplication automatic — two users learning "send WhatsApp message"
 * produce the same skillId → treated as UPDATE not INSERT in Room/Supabase.
 *
 * Also contains the Intent Taxonomy constants.
 */
object SkillIdentity {

    /**
     * Generate a canonical 16-char hex ID for a skill.
     *
     * Examples:
     *   com.whatsapp + messaging/send_message     → "a3f8c2d1e4b7a9c0"
     *   com.whatsapp + calling/make_call          → "b1d4e7f2c9a3b8d1"
     */
    fun generateCanonicalId(
        appPackage: String,
        intentCategory: String,
        intentAction: String
    ): String {
        val canonical = "${appPackage}::${intentCategory}::${intentAction}"
        return canonical.sha256().take(16)
    }

    /**
     * ID of one variant of a task: the same task on a different skin, Android
     * version group or app major version gets a different ID, so variants never
     * overwrite each other. `null` skin / app version mean "any".
     */
    fun variantId(
        intentId: String,
        osSkin: String?,
        osClassTag: String,
        appMajorVersion: String?
    ): String = "$intentId::${osSkin ?: "any"}::$osClassTag::${appMajorVersion ?: "any"}"
        .sha256().take(20)

    /** Normalise an LLM-provided category/action into a stable snake_case token. */
    fun cleanToken(value: String?, fallback: String): String =
        value?.lowercase()?.replace(Regex("[^a-z0-9]+"), "_")?.trim('_')?.takeIf { it.isNotEmpty() }
            ?: fallback

    /**
     * Infer intent category + action from a user goal using keyword heuristics.
     * Used when generating skill IDs from LLM-planned tasks.
     *
     * Returns Pair(category, action).
     */
    fun inferIntent(goal: String, appPackage: String): Pair<String, String> {
        val lower = goal.lowercase()

        // Payment intents
        if (lower.containsAny("pay", "send money", "transfer", "upi")) {
            return "payment" to "send_money"
        }
        if (lower.containsAny("balance", "check balance")) {
            return "payment" to "check_balance"
        }

        // Messaging intents — a call word wins over app names ("call Mom on WhatsApp"),
        // but not over a message word before it ("text Mom to call me back").
        val callAt = CALL_WORDS.find(lower)?.range?.first   // whole words: "bring" isn't "ring"
        val messageAt = MESSAGE_WORDS.find(lower)?.range?.first
        if (callAt != null && (messageAt == null || callAt < messageAt)) {
            return "messaging" to "make_call"
        }
        if (lower.containsAny("message", "msg", "text", "whatsapp", "chat")) {
            return "messaging" to "send_message"
        }

        // Food intents
        if (lower.containsAny("reorder", "order again", "last order")) {
            return "food" to "reorder_last"
        }
        if (lower.containsAny("order food", "food", "zomato", "swiggy")) {
            return "food" to "search_item"
        }

        // Navigation intents
        if (lower.containsAny("navigate", "directions", "go to", "take me to", "route")) {
            return "navigation" to "navigate_to"
        }

        // Media intents
        if (lower.containsAny("youtube", "video", "watch", "play")) {
            return "media" to "search_play"
        }

        // Email intents — compose first, otherwise "send an email" matches "mail" → read_inbox
        if (lower.containsAny("compose", "send email", "send an email", "write email", "send mail", "reply to")) {
            return "email" to "compose_send"
        }
        if (lower.containsAny("gmail", "email", "mail", "inbox")) {
            return "email" to "read_inbox"
        }

        // Settings intents
        if (lower.containsAny("wifi", "bluetooth", "settings", "toggle")) {
            return "settings" to "toggle_setting"
        }

        // Default — derive from app package
        val appName = appPackage.split(".").lastOrNull() ?: "unknown"
        return appName to "unknown_action"
    }

    // ── Intent Taxonomy Constants ─────────────────────────────────

    object Category {
        const val MESSAGING   = "messaging"
        const val PAYMENT     = "payment"
        const val FOOD        = "food"
        const val NAVIGATION  = "navigation"
        const val MEDIA       = "media"
        const val EMAIL       = "email"
        const val SETTINGS    = "settings"
        const val SHOPPING    = "shopping"
        const val TRAVEL      = "travel"
    }

    object Action {
        // Messaging
        const val SEND_MESSAGE   = "send_message"
        const val MAKE_CALL      = "make_call"
        const val READ_LAST      = "read_last_message"

        // Payment
        const val SEND_MONEY     = "send_money"
        const val CHECK_BALANCE  = "check_balance"

        // Food
        const val REORDER_LAST   = "reorder_last"
        const val SEARCH_ITEM    = "search_item"
        const val TRACK_ORDER    = "track_order"

        // Navigation
        const val NAVIGATE_TO    = "navigate_to"
        const val SEARCH_PLACE   = "search_place"

        // Media
        const val SEARCH_PLAY    = "search_play"

        // Email
        const val COMPOSE_SEND   = "compose_send"
        const val READ_INBOX     = "read_inbox"

        // Settings
        const val TOGGLE_SETTING = "toggle_setting"
    }

    // ── Helpers ───────────────────────────────────────────────────

    private fun String.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest(this.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private val CALL_WORDS = Regex("\\b(call|calling|ring|dial)\\b")
    private val MESSAGE_WORDS = Regex("\\b(message|messages|msg|text|texts)\\b")

    private fun String.containsAny(vararg terms: String): Boolean =
        terms.any { this.contains(it) }
}
