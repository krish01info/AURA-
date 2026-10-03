package com.aura.skills

import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillLearner"

/**
 * SkillLearner — auto-generates a reusable skill from every successful LLM task.
 *
 * Called automatically after every successful Groq-planned task.
 * The skill is saved locally first (instant — works offline next time),
 * then queued for Supabase cloud upload (other users benefit).
 *
 * Key SKILL-SYSTEM.md behaviours:
 *  - Uses SkillIdentity.generateCanonicalId() for dedup-safe IDs
 *  - Auto-tags skills with OsFingerprint (OS class, skin, API level)
 *  - If skill already exists: just increment usage, don't overwrite
 */
@Singleton
class SkillLearner @Inject constructor(
    private val skillPackDao: SkillPackDao,
    private val osFingerprint: OsFingerprint
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * Learn from a successfully completed LLM-planned task.
     *
     * @param goal         Original user goal
     * @param steps        The action steps that succeeded
     * @param appPackage   Primary app used (detected from first open_app step)
     */
    suspend fun learnFromSuccess(
        goal: String,
        steps: List<ActionStep>,
        appPackage: String
    ) {
        try {
            // Use SkillIdentity for canonical dedup-safe IDs
            val (intentCategory, intentAction) = SkillIdentity.inferIntent(goal, appPackage)
            val canonicalId = SkillIdentity.generateCanonicalId(appPackage, intentCategory, intentAction)

            // If skill already exists — just increment usage, don't overwrite
            val existing = skillPackDao.getSkill(canonicalId)
            if (existing != null) {
                skillPackDao.incrementUsage(canonicalId)
                Log.d(TAG, "Updated existing skill: $canonicalId")
                return
            }

            val keywords = extractKeywords(goal, appPackage)
            val params = extractParameters(steps)
            val os = osFingerprint

            val skill = SkillPackEntity(
                skillId = canonicalId,
                appPackage = appPackage,
                intentCategory = intentCategory,
                intentAction = intentAction,
                displayName = buildDisplayName(goal, appPackage),
                triggerKeywords = json.encodeToString(keywords),
                parameters = json.encodeToString(params),
                steps = json.encodeToString(steps),
                riskLevel = inferRiskLevel(steps),
                createdBy = "local",
                // OS auto-tagging (SKILL-SYSTEM.md §OS Classification)
                targetApiLevel = os.apiLevel,
                minApiLevel = os.apiLevel,   // conservative: only tested on this OS
                maxApiLevel = null,            // assume forward-compat until proven otherwise
                osClassTag = os.osClassTag,
                osSkin = os.osSkin,
                skinVersion = os.skinVersion,
                deviceModel = os.deviceModel,
                usageCount = 1,
                successCount = 1,
                successRate = 1.0f,
                description = "Auto-learned: $goal"
            )

            skillPackDao.upsert(skill)
            Log.i(TAG, "✅ Learned new skill: $canonicalId [$intentCategory/$intentAction] (${keywords.size} keywords)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to learn skill: ${e.message}", e)
        }
    }

    /**
     * Record a failure for a skill — updates success rate.
     */
    suspend fun recordFailure(skillId: String) {
        val skill = skillPackDao.getSkill(skillId) ?: return
        val total = skill.usageCount.toFloat()
        val successes = (skill.successRate * (total - 1)).coerceAtLeast(0f)
        val newRate = successes / total
        skillPackDao.updateSuccessRate(skillId, newRate)
        if (newRate < 0.5f) {
            skillPackDao.markStale(skillId)
            Log.w(TAG, "Skill marked stale (success rate ${newRate * 100}%): $skillId")
        }
    }

    // ──────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────

    private fun buildDisplayName(goal: String, appPackage: String): String {
        val appName = appPackage.split(".").lastOrNull()
            ?.replaceFirstChar { it.uppercase() } ?: appPackage
        return "$appName: ${goal.take(40)}"
    }

    private fun generateSkillId(goal: String, appPackage: String): String {
        // Legacy fallback — prefer SkillIdentity.generateCanonicalId()
        val appName = appPackage.split(".").lastOrNull() ?: appPackage
        val words = goal.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
            .take(3)
        return "${appName}_${words.joinToString("_")}"
    }

    private fun extractKeywords(goal: String, appPackage: String): List<String> {
        val appName = appPackage.split(".").lastOrNull() ?: appPackage
        val words = goal.lowercase()
            .split(Regex("[^a-z0-9]+"))
            .filter { it.length > 2 }
        return (words + listOf(appName)).distinct().take(10)
    }

    private fun extractParameters(steps: List<ActionStep>): List<String> {
        val params = mutableListOf<String>()
        steps.forEach { step ->
            if (step.action == ActionStep.TYPE && step.inputText != null) params.add("input_text")
            if (step.action == ActionStep.FIND_ELEMENT && step.text != null) params.add("contact_name")
        }
        return params.distinct()
    }

    private fun inferRiskLevel(steps: List<ActionStep>): String {
        return when {
            steps.any { it.action == ActionStep.CONFIRM_SEND && it.risk == "CRITICAL" } -> "CRITICAL"
            steps.any { it.action == ActionStep.CONFIRM_SEND } -> "HIGH"
            steps.any { it.action == ActionStep.TYPE || it.action == ActionStep.TAP } -> "MEDIUM"
            else -> "LOW"
        }
    }
}
