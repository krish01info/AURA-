package com.aura.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.data.model.AuditLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the audit_log table.
 *
 * Provides insert and query operations for the security audit log.
 * All queries return newest-first ordering for display purposes.
 */
@Dao
interface AuditLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: AuditLogEntity)

    /** All audit entries, newest first. Used by future Audit Log screen. */
    @Query("SELECT * FROM audit_log ORDER BY timestamp DESC")
    fun getAll(): Flow<List<AuditLogEntity>>

    /** Entries for a specific task. */
    @Query("SELECT * FROM audit_log WHERE taskId = :taskId ORDER BY timestamp ASC")
    suspend fun getForTask(taskId: String): List<AuditLogEntity>

    /** Entries filtered by outcome — used by AnomalyDetector (Phase 7). */
    @Query("SELECT * FROM audit_log WHERE outcome = :outcome ORDER BY timestamp DESC")
    suspend fun getByOutcome(outcome: String): List<AuditLogEntity>

    /** Total count of USER_DENIED events — anomaly signal. */
    @Query("SELECT COUNT(*) FROM audit_log WHERE outcome = 'USER_DENIED'")
    suspend fun getDeniedCount(): Int

    /** Delete entries older than the given timestamp (data hygiene). */
    @Query("DELETE FROM audit_log WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)

    /**
     * Count HIGH/CRITICAL actions since [sinceTimestamp] — used by AnomalyDetector rate limiter.
     */
    @Query("SELECT COUNT(*) FROM audit_log WHERE (riskLevel = 'HIGH' OR riskLevel = 'CRITICAL') AND timestamp >= :sinceTimestamp")
    suspend fun getRecentHighRisk(sinceTimestamp: Long): Int

    /**
     * Average payment amount extracted from CRITICAL audit log entries.
     * Used by AnomalyDetector to detect unusually large payments.
     * Returns null if no historical data exists.
     */
    @Query("SELECT AVG(CAST(SUBSTR(detail, INSTR(detail, '\u20b9') + 1, 10) AS FLOAT)) FROM audit_log WHERE riskLevel = 'CRITICAL' AND outcome = 'USER_APPROVED' LIMIT 50")
    suspend fun getAveragePaymentAmount(): Float?
}
