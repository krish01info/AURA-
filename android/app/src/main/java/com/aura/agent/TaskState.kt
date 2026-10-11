package com.aura.agent

import com.aura.data.model.ActionStep

/**
 * Sealed class representing every state the AURA task state machine can be in.
 *
 * State transitions:
 *   Created → Planning → Executing → [WaitingForUser →] Verifying → Completed
 *                                                                  ↘ Failed
 *   Any state → Cancelled (user taps Emergency Stop)
 */
sealed class TaskState {

    /** Initial state — task has been created but not yet started. */
    data object Created : TaskState()

    /** LLM is generating the action plan for the goal. */
    data object Planning : TaskState()

    /** Agent is executing action steps one by one. */
    data class Executing(
        val currentStep: ActionStep,
        val stepIndex: Int,
        val totalSteps: Int
    ) : TaskState()

    /**
     * Paused — waiting for user approval of a HIGH or CRITICAL risk action.
     * Execution resumes only after the user taps Allow/Confirm.
     */
    data class WaitingForUser(
        val riskAction: ActionStep,
        val riskLevel: RiskLevel,
        val onAllow: suspend () -> Unit,
        val onDeny: () -> Unit,
        /** Non-null when AnomalyDetector flagged something suspicious. */
        val anomalyWarning: String? = null
    ) : TaskState()

    /**
     * Paused — the app asked for its lock (fingerprint / PIN / pattern) or the phone is locked.
     * AURA never enters PINs; it waits for the user to unlock, then retries the step.
     */
    data class WaitingForUnlock(val appName: String?) : TaskState()

    /**
     * A live task is listening: each of [words] runs its steps when heard (until a stop word).
     * [fired] = words acted on so far, [lastHeard] = the latest, [status] = a note to show (or null).
     */
    data class Live(
        val words: List<String>,
        val fired: Int,
        val lastHeard: String?,
        val status: String?
    ) : TaskState()

    /** All steps executed — agent is verifying the outcome. */
    data object Verifying : TaskState()

    /** Task completed successfully. */
    data class Completed(val summary: String) : TaskState()

    /** Task failed. May be retried depending on the reason. */
    data class Failed(
        val reason: String,
        val isRetryable: Boolean = false
    ) : TaskState()

    /** User cancelled via Emergency Stop or explicit cancel. */
    data object Cancelled : TaskState()
}

/**
 * Risk levels used throughout the policy engine and task state machine.
 */
enum class RiskLevel {
    LOW,       // Auto-execute
    MEDIUM,    // Log and execute
    HIGH,      // Show confirmation dialog
    CRITICAL   // Require biometric + confirmation dialog
}
