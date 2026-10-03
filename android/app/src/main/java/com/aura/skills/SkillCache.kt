package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.SkillPackEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillCache"
private const val MAX_CACHE_SIZE = 20

/**
 * SkillCache — L1 in-memory hot cache (LRU, top-20 most-used skills).
 *
 * Layer 1 of the 3-layer cache:
 *   L1: RAM (this class) — <1ms, your top 20 most-used skills
 *   L2: Room DB          — 5–15ms, all skills you've ever used
 *   L3: Supabase cloud   — 300–800ms, community skills
 *
 * Uses LinkedHashMap in accessOrder=true mode for automatic LRU eviction.
 * The least recently used skill is evicted when the cache is full.
 *
 * Warmed up on app startup by reading top 20 skills from Room DB.
 */
@Singleton
class SkillCache @Inject constructor(
    private val localDao: SkillPackDao
) {
    // accessOrder=true = LRU semantics: get() moves entry to end, oldest at front
    private val hotCache = object : LinkedHashMap<String, SkillPackEntity>(
        MAX_CACHE_SIZE + 4, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: Map.Entry<String, SkillPackEntity>
        ): Boolean {
            val evict = size > MAX_CACHE_SIZE
            if (evict) Log.d(TAG, "LRU evicted: ${eldest.key}")
            return evict
        }
    }

    /**
     * Warm up the L1 cache on app startup.
     * Reads the top 20 most-used skills from Room DB into RAM.
     * Should be called once from AURAApplication.onCreate() after Hilt injection.
     */
    suspend fun warmUp() {
        val topSkills = localDao.getTopSkillsByUsage(limit = MAX_CACHE_SIZE)
        synchronized(hotCache) {
            topSkills.forEach { skill ->
                hotCache[skill.skillId] = skill
            }
        }
        Log.i(TAG, "✅ Warmed up with ${topSkills.size} skills")
    }

    /**
     * L1 lookup by keyword — instant, no disk/network.
     * Returns the best non-stale skill matching the keyword.
     */
    fun get(keyword: String): SkillPackEntity? {
        return synchronized(hotCache) {
            hotCache.values.firstOrNull { skill ->
                !skill.isStale &&
                skill.triggerKeywords.contains(keyword, ignoreCase = true)
            }
        }
    }

    /**
     * Get all hot-cached skills matching any of the keywords.
     * Used for multi-keyword scoring in SkillRouter.
     */
    fun getAll(keywords: List<String>): List<SkillPackEntity> {
        return synchronized(hotCache) {
            hotCache.values.filter { skill ->
                !skill.isStale &&
                keywords.any { kw -> skill.triggerKeywords.contains(kw, ignoreCase = true) }
            }
        }
    }

    /**
     * Promote a skill from disk/cloud to the RAM cache.
     * Called after a L2/L3 cache hit so next access is instant.
     */
    fun promote(skill: SkillPackEntity) {
        synchronized(hotCache) {
            hotCache[skill.skillId] = skill
        }
        Log.d(TAG, "Promoted to L1: ${skill.skillId}")
    }

    /**
     * Record a cache hit — updates the LRU order.
     */
    fun recordHit(skillId: String) {
        synchronized(hotCache) {
            hotCache[skillId]?.let { existing ->
                // Re-insert to bump LRU order; also increment local usageCount
                hotCache[skillId] = existing.copy(usageCount = existing.usageCount + 1)
            }
        }
    }

    /**
     * Invalidate a skill from the RAM cache (e.g. after marked stale).
     */
    fun invalidate(skillId: String) {
        synchronized(hotCache) {
            hotCache.remove(skillId)
        }
    }

    /** Returns the number of skills currently in hot cache. */
    fun size(): Int = synchronized(hotCache) { hotCache.size }

    /** Peek at all cached skills (for debug/UI). */
    fun snapshot(): List<SkillPackEntity> =
        synchronized(hotCache) { hotCache.values.toList() }
}
