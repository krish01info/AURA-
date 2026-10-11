package com.aura.skills

import android.net.Uri
import com.aura.agent.ActionExecutor
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * CloudSkillVetting — checks a skill downloaded from the community store before this phone
 * will run it. Anyone can upload, so a downloaded skill must not be able to do anything risky
 * without asking. Rejected skills are simply not used (the LLM plans instead).
 *
 * A downloaded skill is rejected if it:
 *  - can't be parsed, is empty or very long, or uses an action this app doesn't know;
 *  - fires an intent outside [ActionExecutor.isAllowedIntent]'s allow-list;
 *  - opens a link with an unexpected scheme (file:, content:, javascript:, intent:…);
 *  - opens a UPI payment link, or taps something like Send / Pay / Delete / Order,
 *    without a confirm_send step before it.
 */
object CloudSkillVetting {

    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_STEPS = 30

    private val LINK_SCHEMES = setOf("http", "https", "market", "upi", "geo", "mailto", "tel", "sms", "smsto", "spotify")

    /** Taps that commit something: these must come after a confirm_send. */
    private val COMMITTING_TAP = Regex(
        "\\b(send|pay|post|publish|buy|order|place order|checkout|check out|delete|remove|transfer|" +
            "submit|confirm|book|share|proceed|uninstall|install|accept|unsubscribe|block)\\b",
        RegexOption.IGNORE_CASE
    )

    /** Why [skill] must not run, or null if it's acceptable. */
    fun problem(skill: SkillPackEntity, parseUri: (String) -> Uri? = { Uri.parse(it) }): String? {
        val steps = try {
            json.decodeFromString(ListSerializer(ActionStep.serializer()), skill.steps)
        } catch (e: Exception) {
            return "steps don't parse"
        }
        if (steps.isEmpty()) return "no steps"
        if (steps.size > MAX_STEPS) return "too many steps (${steps.size})"

        if (steps.flatMap { it.flatten() }.size > MAX_STEPS * 2) return "too many steps"
        var confirmed = false
        for (st in steps) {
            stepProblem(st, confirmed, parseUri)?.let { return it }
            if (st.action.trim().lowercase() == ActionStep.CONFIRM_SEND) confirmed = true
            // Steps a live task runs on a spoken word never get a confirmation, so they
            // must be harmless on their own.
            for (nested in st.flatten().drop(1)) {
                if (nested.action.trim().lowercase() == ActionStep.LISTEN) return "nested listen"
                stepProblem(nested, false, parseUri)?.let { return "live step: $it" }
            }
        }
        return null
    }

    private fun stepProblem(st: ActionStep, confirmed: Boolean, parseUri: (String) -> Uri?): String? {
        val action = st.action.trim().lowercase()
        if (action !in ActionStep.KNOWN_ACTIONS) return "unknown action $action"
        when (action) {
            ActionStep.CLARIFY -> return "contains a clarify step"
            ActionStep.INTENT -> {
                val intentAction = st.intentAction?.trim().orEmpty().ifEmpty { "android.intent.action.VIEW" }
                val uri = st.uri?.let { parseUri(it.replace(SLOT, "x")) }
                if (!ActionExecutor.isAllowedIntent(intentAction, uri, st.mime)) return "intent $intentAction not allowed"
                if (isUpi(st.uri) && !confirmed) return "payment link without confirmation"
            }
            ActionStep.OPEN_LINK -> {
                val scheme = st.uri?.substringBefore(':')?.trim()?.lowercase()
                    ?: return "link step without a link"
                if (scheme !in LINK_SCHEMES) return "link scheme $scheme not allowed"
                if (isUpi(st.uri) && !confirmed) return "payment link without confirmation"
            }
            ActionStep.TAP -> {
                val target = listOfNotNull(st.text, st.viewId?.substringAfter(":id/")?.replace('_', ' '))
                    .joinToString(" ")
                if (!confirmed && COMMITTING_TAP.containsMatchIn(target)) {
                    return "taps \"$target\" without asking first"
                }
            }
        }
        return null
    }

    private fun isUpi(uri: String?) = uri?.trim()?.lowercase()?.startsWith("upi:") == true

    private val SLOT = Regex("\\{[a-z_]+\\}")
}
