package com.aura.agent

import android.util.Log
import com.aura.data.model.ActionStep
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillRouter"

/**
 * SkillRouter — routes incoming goals to the fastest execution path.
 *
 * Lookup chain (Phase 2b will fill these in):
 *   1. Local Room DB cache  → instant, offline
 *   2. Cloud Supabase store → shared community skills
 *   3. LLM (Groq)          → always available, slowest
 *
 * For Phase 1, this always returns null (no skills loaded yet),
 * causing [TaskManager] to fall through to LLM planning.
 * The slot is here so Phase 2b can plug in without touching TaskManager.
 *
 * @see TaskManager — checks [findSkill] before calling GroqClient.plan()
 */
@Singleton
class SkillRouter @Inject constructor() {

    /**
     * Try to find a cached skill for the given goal.
     *
     * @param goal  The user's natural language goal.
     * @return      A pre-built list of [ActionStep]s if a matching skill exists,
     *              or null if the goal must be planned by the LLM.
     */
    suspend fun findSkill(goal: String): List<ActionStep>? {
        // Phase 1: no skill cache yet — always fall through to LLM
        Log.d(TAG, "Phase 1 stub — no skill cache. goal=$goal")
        return null

        // Phase 2b: implement as:
        //   return localSkillCache.find(goal)
        //       ?: cloudSkillStore.download(goal)
    }

    /**
     * Save a successfully completed LLM task as a reusable skill.
     * Called by [TaskManager] after every successful Groq-planned task.
     *
     * Phase 1: no-op stub.
     */
    suspend fun learnSkill(goal: String, steps: List<ActionStep>) {
        // Phase 2b: localSkillCache.save(goal, steps)
        //           cloudSkillStore.upload(goal, steps)
        Log.d(TAG, "Phase 1 stub — skill learning deferred. goal=$goal, steps=${steps.size}")
    }
}
