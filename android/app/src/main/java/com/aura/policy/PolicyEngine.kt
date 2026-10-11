package com.aura.policy

import com.aura.agent.RiskLevel
import com.aura.data.model.ActionStep
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PolicyEngine — classifies every action step by risk level before execution.
 *
 * Risk levels:
 * - LOW      → auto-execute silently
 * - MEDIUM   → log and execute
 * - HIGH     → show confirmation dialog, require user tap Allow
 * - CRITICAL → require biometric + confirmation dialog
 *
 * This is a non-negotiable security layer — it runs for EVERY action.
 */
@Singleton
class PolicyEngine @Inject constructor() {

    /**
     * Classify an action step by risk level.
     *
     * The risk field in the ActionStep can override the default classification
     * (e.g., if the LLM explicitly marks a step as CRITICAL).
     */
    fun classify(step: ActionStep): RiskLevel {
        // LLM-specified override takes precedence if it's more restrictive
        val llmRisk = step.risk?.toRiskLevelOrNull()

        val defaultRisk = classifyByAction(step)

        // Use whichever is higher (more restrictive)
        return if (llmRisk != null && llmRisk.ordinal > defaultRisk.ordinal) {
            llmRisk
        } else {
            defaultRisk
        }
    }

    private fun classifyByAction(step: ActionStep): RiskLevel = when (step.action) {
        // LOW — navigation and reading, fully reversible
        ActionStep.OPEN_APP,
        ActionStep.BACK,
        ActionStep.HOME,
        ActionStep.WAIT,
        ActionStep.FIND_ELEMENT,
        ActionStep.READ_TEXT,
        ActionStep.VERIFY        -> RiskLevel.LOW

        // LOW — shortcuts only OPEN an app / set a reminder; nothing is sent or paid.
        // (A send still needs a tap, which the planner gates with confirm_send.)
        ActionStep.DIAL,          // opens the dialer, doesn't call
        ActionStep.SMS,           // opens the composer, doesn't send
        ActionStep.ALARM,
        ActionStep.TIMER         -> RiskLevel.LOW
        // MEDIUM — the user asked to call; the popup showed the name with Cancel before running.
        ActionStep.CALL          -> RiskLevel.MEDIUM
        ActionStep.OPEN_LINK     -> if (isPayment(step)) RiskLevel.HIGH else RiskLevel.MEDIUM
        // Generic intents only open/prefill screens (allow-listed in ActionExecutor);
        // a UPI payment link still asks first — the payment app then asks for the PIN.
        ActionStep.INTENT        -> if (isPayment(step)) RiskLevel.HIGH else RiskLevel.MEDIUM

        // Media keys / reading aloud change nothing important.
        ActionStep.MEDIA,
        ActionStep.NOW_PLAYING,
        ActionStep.READ_NOTIFICATIONS -> RiskLevel.LOW

        // Replying sends a message straight away — always ask.
        ActionStep.REPLY         -> RiskLevel.HIGH

        // MEDIUM — UI interaction but no destructive effect
        ActionStep.SCROLL,
        ActionStep.SWIPE         -> RiskLevel.MEDIUM

        // HIGH — typing and tapping can submit forms, send messages
        ActionStep.TAP,
        ActionStep.TYPE          -> RiskLevel.MEDIUM  // elevated to HIGH by confirm_send

        // HIGH — explicit confirmation gates
        ActionStep.CONFIRM_SEND  -> classifyConfirmSend(step)

        // Default to HIGH for unknown actions (safe fallback)
        else                     -> RiskLevel.HIGH
    }

    /**
     * Classifies a confirm_send step based on its declared risk field.
     * Defaults to HIGH if not specified.
     */
    private fun classifyConfirmSend(step: ActionStep): RiskLevel {
        return step.risk?.toRiskLevelOrNull() ?: RiskLevel.HIGH
    }

    private fun isPayment(step: ActionStep): Boolean =
        step.uri?.trim()?.lowercase()?.startsWith("upi:") == true

    private fun String.toRiskLevelOrNull(): RiskLevel? = try {
        RiskLevel.valueOf(this.uppercase())
    } catch (_: IllegalArgumentException) {
        null
    }
}
