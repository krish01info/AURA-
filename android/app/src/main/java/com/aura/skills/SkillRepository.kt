package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillRepository"

/**
 * SkillRepository — skill data access for the UI (Skill Store, Dashboard).
 *
 * Finding a skill for a goal is NOT done here: that is `agent/SkillRouter`
 * (RAM → disk → Room → cloud, OS-aware, pattern-matched). Keeping one lookup
 * path avoids the two drifting apart.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val localDao: SkillPackDao,
    private val skillCache: SkillCache,
    private val diskCache: SkillDiskCache
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Decode a variant's step template. */
    fun toActionSteps(skill: SkillPackEntity): List<ActionStep>? = try {
        json.decodeFromString(ListSerializer(ActionStep.serializer()), skill.steps)
    } catch (e: Exception) {
        Log.e(TAG, "Failed to deserialize steps for ${skill.skillId}: ${e.message}")
        null
    }

    /** Save a skill variant to Room, disk and RAM. */
    suspend fun saveSkill(skill: SkillPackEntity) {
        localDao.upsert(skill)
        diskCache.put(skill)
        skillCache.promote(skill)
        skillCache.flushEvictions()
    }

    /** All skill variants as a Flow for the Skill Store UI. */
    fun getAllSkillsFlow() = localDao.getAllSkills()

    /** Variants not uploaded to the cloud yet. */
    suspend fun getUnsynced(): List<SkillPackEntity> = localDao.getUnsynced()

    /** Mark a variant as synced after cloud upload. */
    suspend fun markSynced(skillId: String) = localDao.markSynced(skillId)
}
