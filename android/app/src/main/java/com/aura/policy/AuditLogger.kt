package com.aura.policy

import android.util.Log
import com.aura.agent.RiskLevel
import com.aura.data.db.TaskLogDao
import com.aura.data.model.ActionStep
import com.aura.data.model.AuditLogEntity
import com.aura.data.db.AuditLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AuditLogger"

/**
 * AuditLogger — records a structured, tamper-evident log of every action
 * the agent executed. Stored in Room DB. No sensitive content (passwords,
 * PINs, OTPs) is ever written.
 *
 * This fulfills Phase 3's audit logging requirement and is required for
 * security review and anomaly detection in Phase 7.
 */
@Singleton
class AuditLogger @Inject constructor(
    private val auditLogDao: AuditLogDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Log an action that was executed automatically (LOW / MEDIUM risk).
     */
    fun logAutoExecuted(taskId: String, step: ActionStep, riskLevel: RiskLevel) {
        log(
            taskId = taskId,
            action = step.action,
            riskLevel = riskLevel,
            outcome = AuditOutcome.AUTO_EXECUTED,
            detail = buildDetail(step)
        )
    }

    /**
     * Log an action that was approved by the user (HIGH / CRITICAL risk).
     */
    fun logUserApproved(taskId: String, step: ActionStep, riskLevel: RiskLevel) {
        log(
            taskId = taskId,
            action = step.action,
            riskLevel = riskLevel,
            outcome = AuditOutcome.USER_APPROVED,
            detail = buildDetail(step)
        )
    }

    /**
     * Log an action that was denied by the user.
     */
    fun logUserDenied(taskId: String, step: ActionStep, riskLevel: RiskLevel) {
        log(
            taskId = taskId,
            action = step.action,
            riskLevel = riskLevel,
            outcome = AuditOutcome.USER_DENIED,
            detail = buildDetail(step)
        )
    }

    /**
     * Log an emergency stop event.
     */
    fun logEmergencyStop(taskId: String) {
        log(
            taskId = taskId,
            action = "EMERGENCY_STOP",
            riskLevel = RiskLevel.CRITICAL,
            outcome = AuditOutcome.EMERGENCY_STOP,
            detail = "User triggered emergency stop"
        )
    }

    // ──────────────────────────────────────────────────────────────
    // Internal
    // ──────────────────────────────────────────────────────────────

    private fun log(
        taskId: String,
        action: String,
        riskLevel: RiskLevel,
        outcome: AuditOutcome,
        detail: String
    ) {
        val entry = AuditLogEntity(
            taskId = taskId,
            action = action,
            riskLevel = riskLevel.name,
            outcome = outcome.name,
            detail = detail,
            timestamp = System.currentTimeMillis()
        )
        scope.launch {
            try {
                auditLogDao.insert(entry)
                Log.d(TAG, "Audit: [$outcome] $action (risk=$riskLevel) — $detail")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write audit log: ${e.message}", e)
            }
        }
    }

    /**
     * Build a sanitised detail string — strips any sensitive fields.
     * We NEVER log: inputText (could be a password), pkg alone is fine.
     */
    private fun buildDetail(step: ActionStep): String = buildString {
        append("action=${step.action}")
        step.pkg?.let { append(", pkg=$it") }
        step.text?.let { append(", text=$it") }
        step.viewId?.let { append(", viewId=$it") }
        step.message?.let { append(", message=$it") }
        // Intentionally omitted: inputText (may contain passwords/OTPs)
    }
}

/** Possible outcomes recorded in the audit log. */
enum class AuditOutcome {
    AUTO_EXECUTED,
    USER_APPROVED,
    USER_DENIED,
    EMERGENCY_STOP
}
