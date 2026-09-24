package com.aura.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.aura.data.model.TaskLogEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the task audit log.
 * Provides reactive Flow-based queries for the UI and suspend functions for writes.
 */
@Dao
interface TaskLogDao {

    /** Observe all tasks in reverse-chronological order (newest first). */
    @Query("SELECT * FROM task_log ORDER BY startedAt DESC")
    fun observeAll(): Flow<List<TaskLogEntity>>

    /** Get the last N tasks — used for memory context injection. */
    @Query("SELECT * FROM task_log ORDER BY startedAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 10): List<TaskLogEntity>

    /** Insert a new task log entry. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: TaskLogEntity)

    /** Update an existing task (e.g., mark as COMPLETED or FAILED). */
    @Query("UPDATE task_log SET status = :status, finishedAt = :finishedAt, errorMessage = :error WHERE taskId = :taskId")
    suspend fun updateStatus(taskId: String, status: String, finishedAt: Long, error: String?)

    /** Clear all task history (user-triggered). */
    @Query("DELETE FROM task_log")
    suspend fun clearAll()

    /** Count of successful tasks. */
    @Query("SELECT COUNT(*) FROM task_log WHERE status = 'COMPLETED'")
    fun observeSuccessCount(): Flow<Int>
}
