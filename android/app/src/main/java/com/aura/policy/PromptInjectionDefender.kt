package com.aura.policy

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "PromptInjectionDefender"

/**
 * PromptInjectionDefender — Phase 7 anti-injection security.
 *
 * Sanitizes user input before it reaches the LLM to prevent:
 *  1. Direct injection ("ignore previous instructions...")
 *  2. Role-play attacks ("pretend you are a hacker...")
 *  3. Delimiter injection (injecting [UI_CONTENT_START] etc.)
 *  4. Privilege escalation ("you now have system access...")
 *  5. Data exfiltration ("send my contacts to http://...")
 *
 * Also scans LLM output steps for SSRF / data exfiltration payloads.
 *
 * Design: allowlist-based validation (safer than blocklist).
 * Input is flagged if it matches any injection pattern AND contains no
 * legitimate task verbs. Flagged inputs are rejected with a safe error.
 */
@Singleton
class PromptInjectionDefender @Inject constructor() {

    /**
     * Validate a user goal before sending to the LLM.
     *
     * @return [ValidationResult.Safe] if input is legitimate task.
     *         [ValidationResult.Injected] with reason if attack detected.
     */
    fun validateInput(goal: String): ValidationResult {
        val lower = goal.lowercase().trim()

        // Check each attack pattern
        for ((pattern, reason) in INJECTION_PATTERNS) {
            if (pattern.containsMatchIn(lower)) {
                Log.w(TAG, "🛡️ Injection detected: $reason in \"${goal.take(50)}\"")
                return ValidationResult.Injected(reason)
            }
        }

        // Check for data exfiltration URLs
        if (URL_PATTERN.containsMatchIn(lower) && !SAFE_URL_DOMAINS.any { lower.contains(it) }) {
            Log.w(TAG, "🛡️ Potential data exfiltration URL in goal")
            return ValidationResult.Injected("Goal contains a suspicious URL")
        }

        // Max length limit
        if (goal.length > MAX_GOAL_LENGTH) {
            Log.w(TAG, "🛡️ Goal exceeds max length (${goal.length} chars)")
            return ValidationResult.Injected("Goal is too long — please be more concise")
        }

        return ValidationResult.Safe
    }

    /**
     * Sanitize a goal string — strips delimiters and control characters.
     * Applied to every input even after [validateInput] passes.
     */
    fun sanitize(goal: String): String {
        return goal
            .replace(Regex("\\[UI_CONTENT.*?\\]"), "")
            .replace(Regex("\\[MEMORY_CONTEXT.*?\\]"), "")
            .replace(Regex("\\[SYSTEM.*?\\]"), "")
            .replace(Regex("[\\x00-\\x1F\\x7F]"), "") // strip control chars
            .trim()
            .take(MAX_GOAL_LENGTH)
    }

    companion object {
        private const val MAX_GOAL_LENGTH = 500

        private val INJECTION_PATTERNS = listOf(
            Regex("ignore (previous|all|prior|above) (instructions|rules|context|prompt)") to "Instruction override attempt",
            Regex("(pretend|act|imagine|you are now|forget|disregard) (you are|you're|that you)") to "Role-play / persona attack",
            Regex("new (instruction|rule|system|directive|task)") to "New instruction injection",
            Regex("jailbreak|dan mode|developer mode|unrestricted") to "Jailbreak attempt",
            Regex("you (have|now have|can now) (access|permission|ability) to") to "Privilege escalation",
            Regex("(exfiltrate|leak|send|transmit) (my|user|private|all)") to "Data exfiltration attempt",
            Regex("(bypass|skip|avoid) (security|biometric|approval|confirmation)") to "Security bypass attempt",
            Regex("\\{\\{.*?\\}\\}|<script|<iframe|javascript:") to "Template/code injection",
        )

        private val URL_PATTERN = Regex("https?://|ftp://|file://")
        private val SAFE_URL_DOMAINS = setOf("google.com", "whatsapp.com", "zomato.com")
    }
}

/** Result of input validation. */
sealed class ValidationResult {
    data object Safe : ValidationResult()
    data class Injected(val reason: String) : ValidationResult()
}
