package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillSeeder"

/**
 * SkillSeeder — built-in starter skills, inserted at startup if missing.
 *
 * Each seed is a **universal** variant (any skin, any Android version, any app
 * version) with `{slot}` templates and phrase patterns, so common tasks work with
 * zero LLM calls from the first launch. If a seed fails on a phone, TaskManager
 * re-plans with the LLM and saves a phone-specific variant, which is preferred
 * from then on — the seed itself is never overwritten.
 *
 * Stats start at zero: they are real counts from this phone, not invented numbers.
 * No payment seeds — payment flows are always planned fresh against the live screen.
 * Shortcut seeds (links / intents) are preferred over screen-tapping ones by SkillRouter.
 */
@Singleton
class SkillSeeder @Inject constructor(
    private val skillPackDao: SkillPackDao
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stepsSerializer = ListSerializer(ActionStep.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    /**
     * Insert built-in seeds that are missing, and refresh built-in seeds whose steps or
     * patterns changed in a new app version (keeping this phone's usage counters).
     * Cheap; safe to call every launch.
     */
    suspend fun seedIfEmpty() {
        var added = 0
        var refreshed = 0
        for (seed in SEEDS) {
            val existing = skillPackDao.getSkill(seed.skillId)
            when {
                existing == null -> { skillPackDao.upsert(seed); added++ }
                existing.createdBy == "builtin" &&
                    (existing.steps != seed.steps || existing.phrasePatterns != seed.phrasePatterns) -> {
                    skillPackDao.upsert(seed.copy(
                        usageCount = existing.usageCount,
                        successCount = existing.successCount,
                        failureCount = existing.failureCount,
                        successRate = existing.successRate,
                        lastUsedAt = existing.lastUsedAt,
                        version = existing.version + 1
                    ))
                    refreshed++
                }
            }
        }
        if (added + refreshed > 0) Log.i(TAG, "✅ Built-in skills: $added added, $refreshed refreshed")
    }

    private fun seed(
        app: String,
        category: String,
        action: String,
        name: String,
        patterns: List<String>,
        steps: List<ActionStep>,
        risk: String,
        description: String
    ): SkillPackEntity {
        val intentId = SkillIdentity.generateCanonicalId(app, category, action)
        val normalized = patterns.map { SkillMatcher.normalize(it) }.filter { SkillMatcher.isUsable(it) }
        return SkillPackEntity(
            skillId = SkillIdentity.variantId(intentId, null, OS_UNIVERSAL, null),
            intentId = intentId,
            appPackage = app,
            intentCategory = category,
            intentAction = action,
            displayName = name,
            triggerKeywords = json.encodeToString(stringsSerializer, SkillMatcher.patternKeywords(normalized)),
            parameters = json.encodeToString(stringsSerializer, normalized.flatMap { SkillMatcher.slots(it) }.distinct()),
            phrasePatterns = json.encodeToString(stringsSerializer, normalized),
            steps = json.encodeToString(stepsSerializer, steps),
            riskLevel = risk,
            minApiLevel = 29, maxApiLevel = null, targetApiLevel = 29,
            osClassTag = OS_UNIVERSAL, osSkin = null, appMajorVersion = null,
            createdBy = "builtin",
            usageCount = 0, successCount = 0, failureCount = 0, successRate = 1.0f,
            description = description
        )
    }

    private val SEEDS: List<SkillPackEntity> by lazy {
        listOf(
            // ── Shortcut seeds (Android intents / links — no screen tapping) ──
            seed(
                app = "com.whatsapp", category = "messaging", action = "send_message",
                name = "WhatsApp: send message",
                patterns = listOf(
                    "send {message_text} to {contact_name} on whatsapp",
                    "send a whatsapp message to {contact_name} saying {message_text}",
                    "send whatsapp to {contact_name} saying {message_text}",
                    "message {contact_name} on whatsapp saying {message_text}",
                    "message {contact_name} on whatsapp {message_text}",
                    "tell {contact_name} on whatsapp {message_text}",
                    "whatsapp {contact_name} saying {message_text}"
                ),
                // Needs the contact's number (ContactResolver). Without contacts permission
                // this variant can't be filled and the search-based one below is used instead.
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_LINK, pkg = "com.whatsapp",
                        uri = "https://wa.me/{contact_number_digits}?text={message_text}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2500L),
                    // The chat must be open (message box on screen) before anything is sent
                    ActionStep(action = ActionStep.FIND_ELEMENT, viewId = "com.whatsapp:id/entry"),
                    ActionStep(action = ActionStep.CONFIRM_SEND,
                        message = "Send \"{message_text}\" to {contact_name} on WhatsApp?", risk = "HIGH"),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/send", text = "Send"),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.whatsapp", text = "{message_text}")
                ),
                risk = "HIGH",
                description = "Send a WhatsApp message to a contact"
            ),
            seed(
                app = "com.whatsapp", category = "messaging", action = "send_message_search",
                name = "WhatsApp: send message (search)",
                patterns = listOf(
                    "send {message_text} to {contact_name} on whatsapp",
                    "send a whatsapp message to {contact_name} saying {message_text}",
                    "message {contact_name} on whatsapp saying {message_text}",
                    "message {contact_name} on whatsapp {message_text}",
                    "tell {contact_name} on whatsapp {message_text}"
                ),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.whatsapp", text = "WhatsApp"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/menuitem_search", text = "Search"),
                    // Type the name into the SEARCH box (named), never into whatever is focused
                    ActionStep(action = ActionStep.TYPE, inputText = "{contact_name}", text = "Search"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
                    // Tap the RESULT (tap skips text boxes), then make sure the chat opened
                    ActionStep(action = ActionStep.TAP, text = "{contact_name}"),
                    ActionStep(action = ActionStep.FIND_ELEMENT, viewId = "com.whatsapp:id/entry"),
                    // Type the message into the chat's message box only
                    ActionStep(action = ActionStep.TYPE, inputText = "{message_text}", viewId = "com.whatsapp:id/entry"),
                    ActionStep(action = ActionStep.CONFIRM_SEND,
                        message = "Send \"{message_text}\" to {contact_name} on WhatsApp?", risk = "HIGH"),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/send"),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.whatsapp", text = "{message_text}")
                ),
                risk = "HIGH",
                description = "Send a WhatsApp message by searching the contact"
            ),
            seed(
                app = "com.android.dialer", category = "calling", action = "call_contact",
                name = "Phone: call",
                patterns = listOf("call {contact_name}", "phone {contact_name}", "dial {contact_name}",
                    "make a call to {contact_name}", "ring {contact_name}"),
                steps = listOf(ActionStep(action = ActionStep.DIAL, number = "{contact_number}")),
                risk = "LOW",
                description = "Open the dialer with a contact's number"
            ),
            seed(
                app = "com.android.mms", category = "messaging", action = "send_sms",
                name = "SMS: text",
                patterns = listOf("text {contact_name} saying {message_text}", "sms {contact_name} saying {message_text}",
                    "send an sms to {contact_name} saying {message_text}", "send a text to {contact_name} saying {message_text}"),
                steps = listOf(ActionStep(action = ActionStep.SMS, number = "{contact_number}", inputText = "{message_text}")),
                risk = "LOW",
                description = "Open an SMS to a contact with the message typed"
            ),
            seed(
                app = "com.android.deskclock", category = "clock", action = "set_alarm",
                name = "Clock: alarm",
                patterns = listOf("set an alarm for {time}", "set alarm for {time}", "wake me up at {time}",
                    "alarm at {time}", "set an alarm at {time}"),
                steps = listOf(ActionStep(action = ActionStep.ALARM, time = "{time}", message = "AURA")),
                risk = "LOW",
                description = "Set an alarm"
            ),
            seed(
                app = "com.android.deskclock", category = "clock", action = "set_timer",
                name = "Clock: timer",
                patterns = listOf("set a timer for {time}", "set timer for {time}", "start a timer for {time}",
                    "timer for {time}", "remind me in {time}"),
                steps = listOf(ActionStep(action = ActionStep.TIMER, time = "{time}", message = "AURA")),
                risk = "LOW",
                description = "Start a timer"
            ),
            seed(
                app = "com.google.android.youtube", category = "media", action = "search_play",
                name = "YouTube: search",
                patterns = listOf(
                    "play {search_query} on youtube",
                    "search {search_query} on youtube",
                    "search youtube for {search_query}",
                    "find {search_query} on youtube",
                    "open youtube and search {search_query}"
                ),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_LINK, pkg = "com.google.android.youtube",
                        uri = "https://www.youtube.com/results?search_query={search_query}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.google.android.youtube")
                ),
                risk = "LOW",
                description = "Search YouTube"
            ),
            seed(
                app = "com.google.android.apps.maps", category = "navigation", action = "navigate_to",
                name = "Maps: directions",
                patterns = listOf(
                    "navigate to {destination}",
                    "take me to {destination}",
                    "directions to {destination}",
                    "show route to {destination}",
                    "how do i get to {destination}"
                ),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_LINK, pkg = "com.google.android.apps.maps",
                        uri = "https://www.google.com/maps/dir/?api=1&destination={destination}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.google.android.apps.maps")
                ),
                risk = "LOW",
                description = "Get directions in Google Maps"
            ),
            // ── App seeds ───────────────────────────────────────────────
            seed(
                app = "com.google.android.gm", category = "email", action = "read_inbox",
                name = "Gmail: open inbox",
                patterns = listOf("open gmail", "open my inbox", "check my email", "check my mail", "open my email"),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.google.android.gm", text = "Gmail"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.google.android.gm")
                ),
                risk = "LOW",
                description = "Open the Gmail inbox"
            ),
            seed(
                app = "com.application.zomato", category = "food", action = "reorder_last",
                name = "Zomato: reorder last order",
                patterns = listOf("reorder my last zomato order", "reorder on zomato", "order my usual from zomato"),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.application.zomato", text = "Zomato"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2500L),
                    ActionStep(action = ActionStep.TAP, text = "Orders"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
                    ActionStep(action = ActionStep.FIND_ELEMENT, text = "Reorder"),
                    ActionStep(action = ActionStep.CONFIRM_SEND,
                        message = "Reorder your last Zomato order?", risk = "HIGH"),
                    ActionStep(action = ActionStep.TAP, text = "Reorder"),
                    ActionStep(action = ActionStep.VERIFY, pkg = "com.application.zomato")
                ),
                risk = "HIGH",
                description = "Reorder your last Zomato order"
            )
        )
    }

    private companion object {
        const val OS_UNIVERSAL = "universal"
    }
}
