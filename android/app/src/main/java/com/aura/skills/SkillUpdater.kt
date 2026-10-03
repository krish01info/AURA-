package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
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
    private val skillLearner: SkillLearner
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

    /**
     * Repair a skill with a new set of steps after LLM re-planned successfully.
     * Bumps the version number so cloud users know there's a fix.
     */
    suspend fun repairSkill(
        skillId: String,
        newSteps: List<ActionStep>,
        goal: String,
        appPackage: String
    ) {
        val existing = localDao.getSkill(skillId)
        val newVersion = (existing?.version ?: 0) + 1
        localDao.markRepaired(skillId, newVersion)
        // Re-learn with fresh OS tags — SkillLearner handles canonical ID dedup
        skillLearner.learnFromSuccess(goal, newSteps, appPackage)
        Log.i(TAG, "🔧 Repaired skill: $skillId → v$newVersion")
    }

    // ── Cloud update check (Phase 6b stub) ───────────────────────
    private suspend fun checkForCloudUpdate(skillId: String) {
        // TODO Phase 6b: val cloudSkill = supabaseApi.getLatestVersion(skillId)
        // if (cloudSkill != null) {
        //     val local = localDao.getSkill(skillId)
        //     if (local != null && cloudSkill.version > local.version) {
        //         localDao.upsert(cloudSkill.toEntity())
        //         Log.i(TAG, "Silent update: $skillId v${local.version} → v${cloudSkill.version}")
        //     }
        // }
        Log.d(TAG, "Cloud check stub — Supabase not yet configured")
    }
}
