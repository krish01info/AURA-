package com.aura.skills

import android.util.Log
import com.aura.cloud.SupabaseSkillApi
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillRepository"

/**
 * SkillRepository — aggregates candidates across all cache layers for SkillRouter.
 *
 * Lookup chain (SKILL-SYSTEM.md §3-Layer Cache):
 *   L1: SkillCache RAM (<1 ms)   — top-20 most-used, LRU eviction
 *   L2: SkillDiskCache (1–5 ms)  — JSON files on internal storage, keyword-indexed
 *   L3: Room DB (5–15 ms)        — source of truth for all locally-learned skills
 *   L4: Supabase cloud (stub)    — community skills (Phase 6b)
 *
 * SkillRouter handles OS-aware multi-factor scoring across the returned candidates.
 */
@Singleton
class SkillRepository @Inject constructor(
    private val localDao: SkillPackDao,
    private val skillCache: SkillCache,
    private val diskCache: SkillDiskCache,
    private val cloudApi: SupabaseSkillApi
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

        // ── L2: Disk cache ────────────────────────────────────────
        val l2Hits = diskCache.findByKeywords(keywords)
        if (l2Hits.isNotEmpty()) {
            val best = l2Hits.maxByOrNull { it.successRate * minOf(it.usageCount / 100f, 1f) + it.successRate }
            if (best != null) {
                skillCache.promote(best)
                skillCache.flushEvictions()
                Log.d(TAG, "L2 disk hit: ${best.skillId}")
                return best
            }
        }

        // ── L3: Room DB ───────────────────────────────────────────
        val candidates = mutableListOf<SkillPackEntity>()
        for (keyword in keywords) {
            candidates.addAll(localDao.findByKeyword(keyword))
        }

        if (candidates.isNotEmpty()) {
            val best = candidates
                .distinctBy { it.skillId }
                .maxByOrNull { it.successRate * minOf(it.usageCount.toFloat(), 1000f) }

            if (best != null) {
                diskCache.put(best)            // write to L2 disk for next time
                skillCache.promote(best)       // pull into L1 RAM
                skillCache.flushEvictions()
                Log.d(TAG, "L3 DB hit: ${best.skillId}")
                return best
            }
        }

        // ── L4: Supabase cloud ─────────────────────────────────────────
        val cloudHits = cloudApi.searchSkills(keywords)
        if (!cloudHits.isNullOrEmpty()) {
            val best = cloudHits.maxByOrNull { it.successRate }
            if (best != null) {
                localDao.upsert(best)          // persist to Room
                diskCache.put(best)            // write to L2 disk
                skillCache.promote(best)       // pull into L1 RAM
                skillCache.flushEvictions()
                Log.d(TAG, "L4 cloud hit: ${best.skillId}")
                return best
            }
        }

        Log.d(TAG, "No skill found for: \"$goal\" → LLM will plan")
        return null
    }

    /**
     * Find all skills matching any of the given keywords across all cache layers.
     * Used by SkillRouter for multi-candidate OS-aware scoring.
     */
    suspend fun findAllMatching(keywords: List<String>): List<SkillPackEntity> {
        val results = mutableListOf<SkillPackEntity>()
        // L1 RAM
        results += skillCache.getAll(keywords)
        // L2 Disk
        results += diskCache.findByKeywords(keywords)
        // L3 Room DB
        for (keyword in keywords) {
            results += localDao.findByKeyword(keyword)
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
