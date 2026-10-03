package com.aura.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * AuditLogEntity — persistent audit log of every agent action.
 *
 * Stored in Room DB. Used by Phase 7 AnomalyDetector and security review.
 * NEVER contains: passwords, PINs, OTPs, or raw inputText.
 */
@Entity(tableName = "audit_log")
data class AuditLogEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),

    /** The task this action belongs to. */
    val taskId: String,

    /** The action type (e.g. "tap", "confirm_send", "EMERGENCY_STOP"). */
    val action: String,

    /** Risk level at time of execution: LOW / MEDIUM / HIGH / CRITICAL. */
    val riskLevel: String,

    /** Outcome: AUTO_EXECUTED / USER_APPROVED / USER_DENIED / EMERGENCY_STOP. */
    val outcome: String,

    /** Sanitised detail string — no sensitive data. */
    val detail: String,

    /** Unix timestamp (ms) when the action occurred. */
    val timestamp: Long = System.currentTimeMillis()
)
