package com.aura.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Room entity representing one task execution in the audit log.
 *
 * Security: No sensitive data (passwords, PINs, OTPs, message content) is stored here.
 * Only the goal text and outcome are kept for UX/analytics.
 */
@Entity(tableName = "task_log")
data class TaskLogEntity(
    @PrimaryKey val taskId: String,
    val goal: String,               // "Send Rahul a WhatsApp message"
    val status: String,             // "COMPLETED" | "FAILED" | "CANCELLED" | "EXECUTING"
    val startedAt: Long,            // Unix timestamp ms
    val finishedAt: Long? = null,   // null if still running
    val appUsed: String? = null,    // e.g. "com.whatsapp"
    val stepCount: Int = 0,         // how many actions were executed
    val errorMessage: String? = null // human-readable failure reason
)

/**
 * Task status constants — mirrors the TaskState sealed class.
 */
object TaskStatus {
    const val CREATED = "CREATED"
    const val PLANNING = "PLANNING"
    const val EXECUTING = "EXECUTING"
    const val WAITING_FOR_USER = "WAITING_FOR_USER"
    const val VERIFYING = "VERIFYING"
    const val COMPLETED = "COMPLETED"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"
}
