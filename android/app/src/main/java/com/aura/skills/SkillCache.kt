package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.SkillPackEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillCache"
private const val MAX_CACHE_SIZE = 20
// How many skills below the top-20 are pre-written to disk on warm-up
private const val DISK_WARMUP_SIZE = 200

/**
 * SkillCache — L1 in-memory hot cache (LRU, top-20 most-used skills).
 *
 * Full 3-layer cache hierarchy:
 *   L1: RAM (this class)     — <1 ms  — top-20 most-used, LRU eviction
 *   L2: Disk (SkillDiskCache)— 1–5 ms — JSON files, keyword-indexed, all promoted skills
 *   L3: Room DB (SkillPackDao)— 5–15ms — source of truth for all skills
 *
 * On warm-up:
 *   • Top-20 by usageCount → loaded into L1 RAM
 *   • Next 200 by usageCount → written to L2 disk so they never need a Room query
 *
 * On LRU eviction:
 *   • Evicted skill is written to L2 disk so it stays fast on next access
 *
 * Lookup flow (SkillRouter):
 *   L1 RAM hit     → return immediately (<1 ms)
 *   L1 miss        → check L2 disk (1–5 ms) → promote back to L1, return
 *   L2 miss        → query L3 Room DB → write to L2 disk, promote to L1, return
 *   L3 miss        → LLM planning
 */
@Singleton
class SkillCache @Inject constructor(
    private val localDao: SkillPackDao,
    private val diskCache: SkillDiskCache
) {
    // accessOrder=true = LRU semantics: get() moves entry to end, oldest at front
    // On eviction the skill is written to the L2 disk cache so it stays fast.
    private val hotCache = object : LinkedHashMap<String, SkillPackEntity>(
        MAX_CACHE_SIZE + 4, 0.75f, true
    ) {
        override fun removeEldestEntry(
            eldest: Map.Entry<String, SkillPackEntity>
        ): Boolean {
            val evict = size > MAX_CACHE_SIZE
            if (evict) {
                Log.d(TAG, "LRU evicted → writing to disk: ${eldest.key}")
                // Write-through to L2 on a background coroutine.
                // We cannot suspend here (LinkedHashMap callback is synchronous),
                // so we use a coroutine scope launched outside.
                evictedSkillsBuffer.add(eldest.value)
            }
            return evict
        }
    }

    // Buffer of skills evicted from L1 that need to be persisted to L2 disk.
    // Flushed by [flushEvictions] which is called from SkillRouter after every lookup.
    private val evictedSkillsBuffer = ArrayDeque<SkillPackEntity>()

    /**
     * Warm up the L1 cache on app startup.
     * Reads the top 20 most-used skills from Room DB into RAM.
     * Should be called once from AURAApplication.onCreate() after Hilt injection.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        // ── L1: top-20 into RAM ───────────────────────────────────
        val topSkills = localDao.getTopSkillsByUsage(limit = MAX_CACHE_SIZE)
        synchronized(hotCache) {
            topSkills.forEach { skill -> hotCache[skill.skillId] = skill }
        }
        Log.i(TAG, "L1 warmed: ${topSkills.size} skills in RAM")

        // ── L2: next DISK_WARMUP_SIZE into disk (non-blocking) ───
        // We fetch all top-(MAX+DISK) skills from DB, skip the ones already
        // in RAM, and write the remainder to disk so cold access avoids Room.
        val diskSkills = localDao.getTopSkillsByUsage(limit = MAX_CACHE_SIZE + DISK_WARMUP_SIZE)
            .drop(MAX_CACHE_SIZE) // skip what's already in L1
        val diskIds = diskCache.allIds()
        var written = 0
        diskSkills.forEach { skill ->
            if (skill.skillId !in diskIds) {
                diskCache.put(skill)
                written++
            }
        }
        Log.i(TAG, "L2 warmed: $written skills written to disk (${diskIds.size} already present)")
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

    /** Replace the RAM copy of [skill] if it's cached (e.g. after counters change). */
    fun refresh(skill: SkillPackEntity) {
        synchronized(hotCache) {
            if (hotCache.containsKey(skill.skillId)) hotCache[skill.skillId] = skill
        }
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
     * Also removes it from the L2 disk cache.
     */
    suspend fun invalidate(skillId: String) {
        synchronized(hotCache) { hotCache.remove(skillId) }
        diskCache.remove(skillId)
    }

    /**
     * Flush any LRU-evicted skills from the in-memory buffer to L2 disk.
     * Called by SkillRouter after each lookup so evictions don't pile up.
     */
    suspend fun flushEvictions() {
        val toFlush = synchronized(evictedSkillsBuffer) {
            val copy = evictedSkillsBuffer.toList()
            evictedSkillsBuffer.clear()
            copy
        }
        toFlush.forEach { skill -> diskCache.put(skill) }
        if (toFlush.isNotEmpty()) {
            Log.d(TAG, "Flushed ${toFlush.size} evicted skill(s) to disk")
        }
    }

    /** Returns the number of skills currently in hot cache. */
    fun size(): Int = synchronized(hotCache) { hotCache.size }

    /** Peek at all cached skills (for debug/UI). */
    fun snapshot(): List<SkillPackEntity> =
        synchronized(hotCache) { hotCache.values.toList() }
}
