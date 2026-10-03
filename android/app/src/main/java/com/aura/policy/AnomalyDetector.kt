package com.aura.policy

import android.util.Log
import com.aura.data.db.AuditLogDao
import com.aura.data.model.ActionStep
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AnomalyDetector"

/**
 * AnomalyDetector — Phase 7 security layer.
 *
 * Detects unusual patterns before executing high-risk actions:
 *  1. Payment amounts that are 5x+ above the user's historical average
 *  2. Burst of HIGH/CRITICAL actions in a short time window (rate limiting)
 *  3. Unusual app access patterns (future)
 *
 * All detected anomalies trigger an extra warning in the ConfirmationDialog
 * before the BiometricPrompt — giving the user a last chance to abort.
 */
@Singleton
class AnomalyDetector @Inject constructor(
    private val auditLogDao: AuditLogDao
) {
    /**
     * Check an action step for anomalies before execution.
     *
     * @return [AnomalyResult.OK] if normal, [AnomalyResult.Suspicious] with a message if flagged.
     */
    suspend fun check(step: ActionStep): AnomalyResult {
        // Check 1: Rate limit — too many HIGH/CRITICAL actions in the last minute
        val rateLimitResult = checkRateLimit()
        if (rateLimitResult is AnomalyResult.Suspicious) return rateLimitResult

        // Check 2: Amount anomaly for payment actions
        if (step.action == ActionStep.CONFIRM_SEND && step.risk == "CRITICAL") {
            val amountResult = checkPaymentAmount(step)
            if (amountResult is AnomalyResult.Suspicious) return amountResult
        }

        return AnomalyResult.OK
    }

    /**
     * Rate limiting: no more than [MAX_HIGH_RISK_PER_MINUTE] HIGH/CRITICAL actions
     * in a 60-second window. Prevents automated attacks / LLM runaway.
     */
    private suspend fun checkRateLimit(): AnomalyResult {
        val oneMinuteAgo = System.currentTimeMillis() - 60_000L
        val recentHighRisk = auditLogDao.getRecentHighRisk(oneMinuteAgo)
        return if (recentHighRisk >= MAX_HIGH_RISK_PER_MINUTE) {
            AnomalyResult.Suspicious(
                "⚠️ Rate limit: $recentHighRisk high-risk actions in the last minute. " +
                "Are you sure this is intended?"
            )
        } else AnomalyResult.OK
    }

    /**
     * Payment anomaly: extract amount from step message and compare to history.
     * e.g. step.message = "Send ₹5000 to Rahul?" → extracts 5000.
     */
    private suspend fun checkPaymentAmount(step: ActionStep): AnomalyResult {
        val requested = extractAmount(step.message ?: step.inputText ?: "") ?: return AnomalyResult.OK
        val avgAmount = auditLogDao.getAveragePaymentAmount() ?: return AnomalyResult.OK

        if (avgAmount <= 0f) return AnomalyResult.OK

        val ratio = requested / avgAmount
        return if (ratio >= ANOMALY_MULTIPLIER) {
            AnomalyResult.Suspicious(
                "⚠️ Amount ₹$requested is ${ratio.toInt()}x your average " +
                "payment of ₹${avgAmount.toInt()}. Please confirm this is correct."
            )
        } else AnomalyResult.OK
    }

    /**
     * Extract a numeric rupee amount from a confirmation message.
     * Matches patterns like: ₹500, Rs.500, 500.00
     */
    private fun extractAmount(text: String): Float? {
        val pattern = Regex("[₹Rs\\.]*([0-9]+(?:\\.[0-9]+)?)")
        val match = pattern.find(text) ?: return null
        return match.groupValues[1].toFloatOrNull()
    }

    companion object {
        private const val MAX_HIGH_RISK_PER_MINUTE = 5
        private const val ANOMALY_MULTIPLIER = 5f
    }
}

/** Result of an anomaly check. */
sealed class AnomalyResult {
    data object OK : AnomalyResult()
    data class Suspicious(val warningMessage: String) : AnomalyResult()
}
