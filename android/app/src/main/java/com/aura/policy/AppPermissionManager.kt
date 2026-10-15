package com.aura.policy

import android.util.Log
import com.aura.data.db.BlockedAppDao
import com.aura.data.db.PermissionRuleDao
import com.aura.data.model.ActionStep
import com.aura.data.model.AppPermission
import com.aura.data.model.PermissionRule
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AppPermissionManager"

/** Result of evaluating a user permission rule against an action. */
sealed class PermissionDecision {
    object Allow   : PermissionDecision()
    object Confirm : PermissionDecision()
    data class Deny(val reason: String) : PermissionDecision()
    object Default : PermissionDecision() // no user rule matched → use PolicyEngine
}

/**
 * AppPermissionManager — per-app action permission firewall.
 *
 * Evaluates user-defined PermissionRules before any action executes.
 * Rules are evaluated highest-priority-first; the first matching rule wins.
 *
 * Evaluation order (from SKILL-SYSTEM.md):
 *   1. Blocked apps check (full app lockout — immediate deny)
 *   2. Per-app + per-action rules, sorted by priority DESC
 *   3. No match → PermissionDecision.Default → PolicyEngine handles it
 *
 * Example rules:
 *   - WhatsApp send_message → ALWAYS_ALLOW
 *   - GPay send_money + amount ≤ ₹500 + contact=Rahul → ALWAYS_ALLOW
 *   - GPay send_money + amount > ₹2000 → ALWAYS_DENY
 *   - ANY app delete_account → ALWAYS_DENY
 */
@Singleton
class AppPermissionManager @Inject constructor(
    private val rulesDao: PermissionRuleDao,
    private val blockedAppsDao: BlockedAppDao
) {
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    /**
     * Evaluate the permission for a given action against user-defined rules.
     *
     * @param action     The action being attempted
     * @param appPackage The app the action targets
     * @param goal       The user's request — used to check contact/amount conditions
     * @param goalIntent Intent of the whole request (e.g. "send_money"), matched against rules
     */
    suspend fun evaluate(
        action: ActionStep,
        appPackage: String,
        goal: String = "",
        goalIntent: String = action.action
    ): PermissionDecision {
        val amount = AmountParser.parse(action.message) ?: AmountParser.parse(goal)
        // ── Step 1: Blocked apps check ───────────────────────────
        if (blockedAppsDao.isBlocked(appPackage)) {
            val reason = "AURA is blocked from $appPackage. Change in Settings → Permissions."
            Log.w(TAG, "🚫 Blocked app: $appPackage")
            return PermissionDecision.Deny(reason)
        }

        // ── Step 2: User permission rules (priority DESC) ────────
        val rules = rulesDao.findRules(
            appPackage = appPackage,
            stepAction = action.action,
            goalIntent = goalIntent
        )

        for (rule in rules) {
            if (!ruleApplies(rule, goal, amount)) continue

            val decision = when (rule.permission) {
                AppPermission.ALWAYS_ALLOW   -> PermissionDecision.Allow
                AppPermission.ALWAYS_CONFIRM -> PermissionDecision.Confirm
                AppPermission.ALWAYS_DENY    -> PermissionDecision.Deny(
                    buildDenyMessage(rule, appPackage, action.action)
                )
            }
            Log.i(TAG, "Rule matched: ${rule.permission} for $appPackage/${action.action}")
            return decision
        }

        // ── Step 3: No rule matched → fall back to PolicyEngine ──
        Log.d(TAG, "No user rule for $appPackage/${action.action} → using PolicyEngine default")
        return PermissionDecision.Default
    }

    /**
     * Check whether a rule's conditions all match.
     * A rule fires only when ALL specified conditions are satisfied.
     */
    private fun ruleApplies(
        rule: PermissionRule,
        goal: String,
        amount: Double?
    ): Boolean {
        // An ALLOW rule must never fire when we can't verify its condition —
        // previously an unknown amount/contact made "allow ≤ ₹500" allow everything.
        val isAllow = rule.permission == AppPermission.ALWAYS_ALLOW

        // Amount condition
        if (rule.maxAmount != null) {
            if (amount == null) { if (isAllow) return false }
            else if (amount > rule.maxAmount) return false
        }

        // Contact allowlist — a listed name must appear in the request
        if (rule.allowedContacts != null) {
            val allowed = parseStringList(rule.allowedContacts).filter { it.isNotBlank() }
            val g = goal.lowercase()
            val mentioned = allowed.any { name ->
                Regex("\\b${Regex.escape(name.lowercase())}\\b").containsMatchIn(g)
            }
            if (!mentioned) return false
        }

        // Time window
        if (rule.allowedTimeStart != null && rule.allowedTimeEnd != null) {
            if (!isWithinTimeWindow(rule.allowedTimeStart, rule.allowedTimeEnd)) return false
        }

        return true
    }

    private fun isWithinTimeWindow(start: String, end: String): Boolean {
        return try {
            val now   = LocalTime.now()
            val tStart = LocalTime.parse(start, timeFormatter)
            val tEnd   = LocalTime.parse(end, timeFormatter)
            if (tStart <= tEnd) now in tStart..tEnd
            else now >= tStart || now <= tEnd // overnight window
        } catch (_: Exception) { true } // parse error → don't block
    }

    private fun buildDenyMessage(
        rule: PermissionRule,
        appPackage: String,
        action: String
    ): String = if (rule.label.isNotBlank()) {
        "Blocked: ${rule.label}"
    } else {
        "You have blocked '$action' for $appPackage. Change in Settings → Permissions."
    }

    private fun parseStringList(json: String): List<String> {
        // kotlinx JSON rather than org.json, so the rule logic runs in plain JVM unit tests
        return try {
            Json.parseToJsonElement(json).jsonArray.map { it.jsonPrimitive.content }
        } catch (_: Exception) {
            json.split(",").map { it.trim().removeSurrounding("\"") }
        }
    }
}
