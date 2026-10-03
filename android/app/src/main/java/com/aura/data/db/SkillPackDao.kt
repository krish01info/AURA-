package com.aura.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.aura.data.model.SkillPackEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for the skill_packs table.
 */
@Dao
interface SkillPackDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(skill: SkillPackEntity)

    @Query("SELECT * FROM skill_packs ORDER BY usageCount DESC")
    fun getAllSkills(): Flow<List<SkillPackEntity>>

    @Query("SELECT * FROM skill_packs ORDER BY usageCount DESC")
    suspend fun getAllSkillsList(): List<SkillPackEntity>

    /** Keyword search — used by SkillRouter for fast local lookup. */
    @Query("SELECT * FROM skill_packs WHERE triggerKeywords LIKE '%' || :keyword || '%' AND isStale = 0")
    suspend fun findByKeyword(keyword: String): List<SkillPackEntity>

    @Query("SELECT * FROM skill_packs WHERE skillId = :skillId")
    suspend fun getSkill(skillId: String): SkillPackEntity?

    /** Top N skills by usage — used by SkillCache.warmUp() to fill L1 RAM cache. */
    @Query("SELECT * FROM skill_packs ORDER BY usageCount DESC LIMIT :limit")
    suspend fun getTopSkillsByUsage(limit: Int): List<SkillPackEntity>

    /** All skill IDs — used by SkillSyncer to detect what we already have locally. */
    @Query("SELECT skillId FROM skill_packs")
    suspend fun getAllSkillIds(): List<String>

    /** OS-class filtered lookup — used by OsMigrationHandler. */
    @Query("SELECT * FROM skill_packs WHERE osClassTag = :osClassTag")
    suspend fun getSkillsByOsClass(osClassTag: String): List<SkillPackEntity>

    /** Increment usage count when a skill is successfully executed. */
    @Query("UPDATE skill_packs SET usageCount = usageCount + 1, lastUsedAt = :timestamp WHERE skillId = :skillId")
    suspend fun incrementUsage(skillId: String, timestamp: Long = System.currentTimeMillis())

    /** Update success rate directly. */
    @Query("UPDATE skill_packs SET successRate = :rate WHERE skillId = :skillId")
    suspend fun updateSuccessRate(skillId: String, rate: Float)

    /** Increment success counter and recompute rate. */
    @Query("UPDATE skill_packs SET successCount = successCount + 1, successRate = CAST(successCount + 1 AS FLOAT) / MAX(1, successCount + 1 + failureCount) WHERE skillId = :skillId")
    suspend fun recordSuccess(skillId: String)

    /** Increment failure counter and recompute rate. */
    @Query("UPDATE skill_packs SET failureCount = failureCount + 1, successRate = CAST(successCount AS FLOAT) / MAX(1, successCount + failureCount + 1) WHERE skillId = :skillId")
    suspend fun recordFailure(skillId: String)

    /** Mark skill as stale (keeps failing — needs repair). */
    @Query("UPDATE skill_packs SET isStale = 1 WHERE skillId = :id")
    suspend fun markStale(id: String)

    /** Unmark stale after repair. */
    @Query("UPDATE skill_packs SET isStale = 0, version = :version WHERE skillId = :id")
    suspend fun markRepaired(id: String, version: Int)

    /** Mark syncedAt timestamp after uploading to cloud. */
    @Query("UPDATE skill_packs SET syncedAt = :timestamp WHERE skillId = :skillId")
    suspend fun markSynced(skillId: String, timestamp: Long = System.currentTimeMillis())

    /** Skills that need syncing to cloud (unsynced or recently updated). */
    @Query("SELECT * FROM skill_packs WHERE syncedAt = 0 AND createdBy = 'local'")
    suspend fun getUnsynced(): List<SkillPackEntity>

    @Query("DELETE FROM skill_packs WHERE skillId = :skillId")
    suspend fun delete(skillId: String)

    @Query("SELECT COUNT(*) FROM skill_packs")
    suspend fun count(): Int
}
