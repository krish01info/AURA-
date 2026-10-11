package com.aura.skills

import android.util.Log
import com.aura.cloud.SupabaseSkillApi
import com.aura.data.db.SkillPackDao
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillUpdater"

/**
 * SkillUpdater — self-healing broken skills.
 *
 * Called after every skill execution (success or failure).
 * Uses SkillHealthMonitor to assess skill health and decide on action:
 *
 *   HEALTHY  (successRate ≥ 0.85) → no action needed
 *   DEGRADED (0.60 ≤ rate < 0.85) → check cloud for newer version (Phase 6b stub)
 *   BROKEN   (rate < 0.60)        → mark stale, evict from cache, trigger LLM repair
 *
 * Once stale, SkillRouter/SkillRepository ignores the skill → Groq LLM
 * re-plans on next invocation → SkillLearner saves the new version as v+1.
 */
@Singleton
class SkillUpdater @Inject constructor(
    private val localDao: SkillPackDao,
    private val skillHealthMonitor: SkillHealthMonitor,
    private val skillCache: SkillCache,
    private val cloudApi: SupabaseSkillApi
) {

    /**
     * Record a successful execution outcome.
     * Updates health counters; no further action for healthy skills.
     */
    suspend fun recordSuccess(skillId: String) {
        skillHealthMonitor.recordSuccess(skillId)
        Log.d(TAG, "✅ Success recorded for skill: $skillId")
    }

    /**
     * Handle a skill execution failure.
     *
     * @param skillId  The skill that failed
     * @param goal     The original user goal (for context logging)
     * @return The resulting SkillHealth (HEALTHY, DEGRADED, or BROKEN)
     */
    suspend fun handleFailure(skillId: String, goal: String): SkillHealth {
        Log.w(TAG, "❌ Failure for skill: $skillId (goal: $goal)")

        val health = skillHealthMonitor.recordFailure(skillId)

        when (health) {
            SkillHealth.BROKEN -> {
                // Skill already marked stale and evicted from cache by SkillHealthMonitor.
                // Next invocation: SkillRouter finds nothing → LLM re-plans.
                Log.w(TAG, "🔴 Skill $skillId is BROKEN — flagging for LLM repair")
                // Phase 6b: cloudApi.flagForRepair(skillId)
            }
            SkillHealth.DEGRADED -> {
                // Check cloud for a newer version silently
                Log.i(TAG, "🟡 Skill $skillId is DEGRADED — checking for cloud update")
                checkForCloudUpdate(skillId)
            }
            SkillHealth.HEALTHY -> {
                Log.d(TAG, "🟢 Skill $skillId still healthy after single failure")
            }
        }

        return health
    }

    // ── Cloud update check ────────────────────────────────────────
    private suspend fun checkForCloudUpdate(skillId: String) {
        val local = localDao.getSkill(skillId) ?: return
        val cloudSkill = cloudApi.getLatestVersion(skillId, local.version)
        if (cloudSkill != null) {
            localDao.upsert(cloudSkill)
            skillCache.invalidate(skillId) // next lookup reloads the new version from Room
            Log.i(TAG, "Silent update: $skillId v${local.version} → v${cloudSkill.version}")
        } else {
            Log.d(TAG, "No cloud update for $skillId (already at latest)")
        }
    }
}
