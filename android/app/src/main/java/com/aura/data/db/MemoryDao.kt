package com.aura.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aura.data.model.MemoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the memory table.
 * All vector similarity is handled in-memory by [MemoryManager] —
 * Room only handles persistence and basic filtering here.
 */
@Dao
interface MemoryDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MemoryEntry)

    @Update
    suspend fun update(entry: MemoryEntry)

    /** All memories, newest first. */
    @Query("SELECT * FROM memory ORDER BY timestamp DESC")
    fun getAll(): Flow<List<MemoryEntry>>

    /** All memories as a plain list for in-memory cosine similarity search. */
    @Query("SELECT * FROM memory ORDER BY timestamp DESC")
    suspend fun getAllList(): List<MemoryEntry>

    /** Memories filtered by app package — for contact/amount suggestions. */
    @Query("SELECT * FROM memory WHERE appUsed = :pkg ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByApp(pkg: String, limit: Int = 20): List<MemoryEntry>

    /** Memories filtered by outcome. */
    @Query("SELECT * FROM memory WHERE outcome = :outcome ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByOutcome(outcome: String, limit: Int = 50): List<MemoryEntry>

    /** Memories whose tags contain a keyword. */
    @Query("SELECT * FROM memory WHERE tags LIKE '%' || :keyword || '%' ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getByKeyword(keyword: String, limit: Int = 10): List<MemoryEntry>

    /** Increment retrieval count for a memory (used = remembered = stronger). */
    @Query("UPDATE memory SET retrievalCount = retrievalCount + 1 WHERE id = :id")
    suspend fun incrementRetrievalCount(id: String)

    /** Total memory count. */
    @Query("SELECT COUNT(*) FROM memory")
    suspend fun count(): Int

    /** Delete oldest entries to keep the DB under a size limit. */
    @Query("DELETE FROM memory WHERE id IN (SELECT id FROM memory ORDER BY timestamp ASC LIMIT :count)")
    suspend fun deleteOldest(count: Int)
}
