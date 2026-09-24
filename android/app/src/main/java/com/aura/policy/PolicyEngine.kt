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
        ActionStep.READ_TEXT     -> RiskLevel.LOW

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

    private fun String.toRiskLevelOrNull(): RiskLevel? = try {
        RiskLevel.valueOf(this.uppercase())
    } catch (_: IllegalArgumentException) {
        null
    }
}
