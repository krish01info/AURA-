package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.SkillPackEntity
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillHealthMonitor"

/** Health classification for a skill. */
enum class SkillHealth {
    HEALTHY,   // successRate >= 0.85 — no action needed
    DEGRADED,  // 0.60 <= successRate < 0.85 — check cloud for newer version
    BROKEN     // successRate < 0.60 — mark stale, trigger LLM repair
}

/**
 * SkillHealthMonitor — assesses and records skill execution outcomes.
 *
 * Called by SkillUpdater after every skill execution:
 *   - HEALTHY → increment usage, nothing else
 *   - DEGRADED → check Supabase for a newer version quietly
 *   - BROKEN → mark isStale, evict from L1 cache, let LLM re-plan + repair
 *
 * Thresholds (from SKILL-SYSTEM.md):
 *   successRate >= 0.85 → HEALTHY
 *   successRate >= 0.60 → DEGRADED
 *   successRate < 0.60  → BROKEN
 */
@Singleton
class SkillHealthMonitor @Inject constructor(
    private val localDao: SkillPackDao,
    private val skillCache: SkillCache,
    private val diskCache: SkillDiskCache
) {
    /**
     * Assess the current health state of a skill.
     */
    fun assess(skill: SkillPackEntity): SkillHealth = when {
        // One failure (e.g. a contact that doesn't exist) shouldn't kill a skill.
        skill.failureCount < MIN_FAILURES_FOR_BROKEN && skill.successRate < 0.60f -> SkillHealth.DEGRADED
        skill.successRate >= 0.85f -> SkillHealth.HEALTHY
        skill.successRate >= 0.60f -> SkillHealth.DEGRADED
        else                       -> SkillHealth.BROKEN
    }

    /**
     * Record a successful execution.
     * Updates DB counters and L1 cache.
     */
    suspend fun recordSuccess(skillId: String) {
        localDao.recordSuccess(skillId)
        localDao.incrementUsage(skillId)
        localDao.getSkill(skillId)?.let { syncCaches(it) }
        Log.d(TAG, "✅ Success recorded for $skillId")
    }

    /**
     * Record a failed execution.
     * Updates DB counters and checks if repair is needed.
     * Returns the resulting SkillHealth.
     */
    suspend fun recordFailure(skillId: String): SkillHealth {
        localDao.recordFailure(skillId)
        val skill = localDao.getSkill(skillId) ?: return SkillHealth.BROKEN

        val health = assess(skill)
        Log.w(TAG, "❌ Failure recorded for $skillId → health=$health (rate=${skill.successRate})")

        if (health == SkillHealth.BROKEN) {
            localDao.markStale(skillId)
            skillCache.invalidate(skillId) // removes it from RAM and disk
            Log.w(TAG, "🔴 Skill $skillId marked BROKEN and evicted from RAM + disk")
        } else {
            syncCaches(skill)
        }

        return health
    }

    /** Keep the RAM and disk copies' counters in line with Room. */
    private suspend fun syncCaches(skill: SkillPackEntity) {
        skillCache.refresh(skill)
        diskCache.update(skill)
    }

    private companion object {
        const val MIN_FAILURES_FOR_BROKEN = 2
    }
}
