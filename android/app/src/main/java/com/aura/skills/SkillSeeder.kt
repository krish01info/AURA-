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
 */
@Singleton
class SkillSeeder @Inject constructor(
    private val skillPackDao: SkillPackDao
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stepsSerializer = ListSerializer(ActionStep.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    /** Insert any built-in seed that isn't in the database yet. Cheap; safe to call every launch. */
    suspend fun seedIfEmpty() {
        var added = 0
        for (seed in SEEDS) {
            if (skillPackDao.getSkill(seed.skillId) == null) {
                skillPackDao.upsert(seed)
                added++
            }
        }
        if (added > 0) Log.i(TAG, "✅ Inserted $added built-in skill(s)")
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
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.whatsapp", text = "WhatsApp"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/menuitem_search", text = "Search"),
                    ActionStep(action = ActionStep.TYPE, inputText = "{contact_name}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1000L),
                    ActionStep(action = ActionStep.TAP, text = "{contact_name}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1000L),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/entry"),
                    ActionStep(action = ActionStep.TYPE, inputText = "{message_text}"),
                    ActionStep(action = ActionStep.CONFIRM_SEND,
                        message = "Send \"{message_text}\" to {contact_name} on WhatsApp?", risk = "HIGH"),
                    ActionStep(action = ActionStep.TAP, viewId = "com.whatsapp:id/send")
                ),
                risk = "HIGH",
                description = "Send a WhatsApp message to a contact"
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
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.google.android.youtube", text = "YouTube"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.TAP, text = "Search"),
                    ActionStep(action = ActionStep.TYPE, inputText = "{search_query}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1000L),
                    ActionStep(action = ActionStep.TAP, text = "{search_query}")
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
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.google.android.apps.maps", text = "Maps"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 2000L),
                    ActionStep(action = ActionStep.TAP, text = "Search here"),
                    ActionStep(action = ActionStep.TYPE, inputText = "{destination}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
                    ActionStep(action = ActionStep.TAP, text = "{destination}"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L),
                    ActionStep(action = ActionStep.TAP, text = "Directions")
                ),
                risk = "LOW",
                description = "Get directions in Google Maps"
            ),
            seed(
                app = "com.google.android.gm", category = "email", action = "read_inbox",
                name = "Gmail: open inbox",
                patterns = listOf("open gmail", "open my inbox", "check my email", "check my mail", "open my email"),
                steps = listOf(
                    ActionStep(action = ActionStep.OPEN_APP, pkg = "com.google.android.gm", text = "Gmail"),
                    ActionStep(action = ActionStep.WAIT, waitMs = 1500L)
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
                    ActionStep(action = ActionStep.TAP, text = "Reorder")
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
