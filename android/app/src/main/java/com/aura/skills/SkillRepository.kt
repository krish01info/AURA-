package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillRepository"

/**
 * SkillRepository — 3-layer skill lookup with OS-aware scoring.
 *
 * Lookup chain (SKILL-SYSTEM.md §3-Layer Cache):
 *   L1: SkillCache RAM (< 1ms)  — top-20 most-used skills, LRU eviction
 *   L2: Room DB    (5–15ms)     — all locally cached/learned skills
 *   L3: null                    — falls through to Groq LLM in TaskManager
 *
 * Note: L3 Supabase cloud lookup is a stub — wired in Phase 6b when
 * Supabase credentials are configured.
 *
 * Selection: SkillRouter handles the full OS-aware multi-factor scoring.
 * SkillRepository's job is to aggregate candidates for SkillRouter.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val localDao: SkillPackDao,
    private val skillCache: SkillCache
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Find the best matching skill for a goal using the 3-layer cache.
     * Returns null if no skill matches (→ LLM will plan it).
     */
    suspend fun findSkill(goal: String): SkillPackEntity? {
        val keywords = extractKeywords(goal)

        // ── L1: RAM hot cache ─────────────────────────────────────
        val l1Hits = skillCache.getAll(keywords)
        if (l1Hits.isNotEmpty()) {
            val best = l1Hits.maxByOrNull { it.successRate * minOf(it.usageCount / 100f, 1f) + it.successRate }
            if (best != null) {
                skillCache.recordHit(best.skillId)
                Log.d(TAG, "L1 cache hit: ${best.skillId}")
                return best
            }
        }

        // ── L2: Room DB ───────────────────────────────────────────
        val candidates = mutableListOf<SkillPackEntity>()
        for (keyword in keywords) {
            candidates.addAll(localDao.findByKeyword(keyword))
        }

        if (candidates.isNotEmpty()) {
            val best = candidates
                .distinctBy { it.skillId }
                .maxByOrNull { it.successRate * minOf(it.usageCount.toFloat(), 1000f) }

            if (best != null) {
                skillCache.promote(best)   // next time it's L1 instant
                Log.d(TAG, "L2 DB hit: ${best.skillId}")
                return best
            }
        }

        // ── L3: Supabase (stub — Phase 6b) ────────────────────────
        // val cloudHit = supabaseApi.searchSkill(keywords)?.toEntity()
        // if (cloudHit != null) {
        //     localDao.upsert(cloudHit)
        //     skillCache.promote(cloudHit)
        //     return cloudHit
        // }

        Log.d(TAG, "No skill found for: \"$goal\" → LLM will plan")
        return null
    }

    /**
     * Find all skills that match any of the given keywords.
     * Used by SkillRouter for multi-candidate scoring.
     */
    suspend fun findAllMatching(keywords: List<String>): List<SkillPackEntity> {
        val results = mutableListOf<SkillPackEntity>()
        // L1 first
        results.addAll(skillCache.getAll(keywords))
        // L2 supplement
        for (keyword in keywords) {
            results.addAll(localDao.findByKeyword(keyword))
        }
        return results.distinctBy { it.skillId }
    }

    /**
     * Convert a SkillPackEntity to executable ActionSteps.
     */
    fun toActionSteps(skill: SkillPackEntity): List<ActionStep>? {
        return try {
            json.decodeFromString(ListSerializer(ActionStep.serializer()), skill.steps)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deserialize steps for ${skill.skillId}: ${e.message}")
            null
        }
    }

    /** Save a new skill or update an existing one. */
    suspend fun saveSkill(skill: SkillPackEntity) {
        localDao.upsert(skill)
        skillCache.promote(skill)
    }

    /** Get all skills as a Flow for the Skill Store UI. */
    fun getAllSkillsFlow() = localDao.getAllSkills()

    /** Get all skills that haven't been synced to cloud yet. */
    suspend fun getUnsynced(): List<SkillPackEntity> = localDao.getUnsynced()

    /** Mark a skill as synced after cloud upload. */
    suspend fun markSynced(skillId: String) = localDao.markSynced(skillId)

    private fun extractKeywords(goal: String): List<String> =
        goal.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .take(6)
}
