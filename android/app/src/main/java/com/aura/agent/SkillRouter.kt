package com.aura.agent

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.SkillPackEntity
import com.aura.skills.OsFingerprint
import com.aura.skills.SkillCache
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillRouter"

/**
 * SkillRouter — 3-layer skill lookup with OS-aware scoring.
 *
 * Lookup chain (L1 → L2 → null):
 *   L1: RAM hot cache (SkillCache) — <1ms, top-20 most-used
 *   L2: Room DB                    — 5–15ms
 *   null → caller falls back to LLM planning
 *
 * When multiple skills match, the scoring system picks the winner.
 *
 * Scoring factors (from SKILL-SYSTEM.md):
 *   1. Keyword match count    (max 50 pts)
 *   2. Exact phrase match     (+20 pts)
 *   3. Success rate           (max 50 pts)
 *   4. Usage count popularity (max 20 pts)
 *   5. Verified badge         (+15 pts)
 *   6. Device-specific match  (+10 pts)
 *   7. App version match      (+5 pts)
 *   8. OS class tag match     (+25 pts exact, +10 pts universal)
 *   9. OS skin match          (+20 pts exact, +8 pts null=any)
 *  10. Target API level match (+10 pts)
 */
@Singleton
class SkillRouter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillCache: SkillCache,
    private val localDao: SkillPackDao,
    private val osFingerprint: OsFingerprint
) {
    /**
     * Find the best skill for the given goal.
     * Returns null if no suitable skill is found — caller invokes LLM.
     */
    suspend fun findBestSkill(goal: String): SkillPackEntity? {
        val keywords = extractKeywords(goal)

        // ── L1: RAM cache — instant ──────────────────────────────────
        val l1Candidates = skillCache.getAll(keywords)
        if (l1Candidates.isNotEmpty()) {
            val best = l1Candidates.maxByOrNull { scoreSkill(it, goal) }
            if (best != null) {
                skillCache.recordHit(best.skillId)
                Log.d(TAG, "L1 cache hit: ${best.skillId} (score=${scoreSkill(best, goal)})")
                return best
            }
        }

        // ── L2: Room DB ──────────────────────────────────────────────
        val l2Candidates = mutableListOf<SkillPackEntity>()
        for (keyword in keywords) {
            l2Candidates += localDao.findByKeyword(keyword)
        }

        if (l2Candidates.isNotEmpty()) {
            val best = l2Candidates.maxByOrNull { scoreSkill(it, goal) }
            if (best != null) {
                skillCache.promote(best) // next time it's instant from RAM
                Log.d(TAG, "L2 DB hit: ${best.skillId} (score=${scoreSkill(best, goal)})")
                return best
            }
        }

        Log.d(TAG, "No skill found for: $goal")
        return null  // LLM takes over
    }

    /**
     * OS-aware multi-factor scoring (from SKILL-SYSTEM.md, page 1454–1498).
     * Higher score = better match.
     */
    private fun scoreSkill(skill: SkillPackEntity, goal: String): Float {
        var score = 0f
        val goalLower = goal.lowercase()
        val keywords = parseKeywordList(skill.triggerKeywords)

        // Factor 1: Keyword match count (max 50 pts)
        val matchCount = keywords.count { goalLower.contains(it.lowercase()) }
        score += matchCount * 10f

        // Factor 2: Exact phrase match bonus (20 pts)
        if (keywords.any { it.lowercase() == goalLower }) score += 20f

        // Factor 3: Success rate (max 50 pts)
        score += skill.successRate * 50f

        // Factor 4: Usage count popularity (max 20 pts)
        score += minOf(skill.usageCount / 500f, 20f)

        // Factor 5: Community verified badge (15 pts)
        if (skill.isVerified) score += 15f

        // Factor 6: Device-specific match (10 pts)
        if (skill.deviceModel != null && skill.deviceModel == Build.MODEL) score += 10f

        // Factor 7: App version match (5 pts)
        val installedVer = getInstalledAppVersion(skill.appPackage)
        if (installedVer != null && installedVer == skill.appVersion) score += 5f

        // ── NEW: OS match bonuses ──────────────────────────────────

        // Factor 8: OS class tag match (25 pts exact, 10 pts universal)
        when {
            skill.osClassTag == osFingerprint.osClassTag -> score += 25f
            skill.osClassTag == "universal"              -> score += 10f
        }

        // Factor 9: OS skin match (20 pts exact, 8 pts null=any)
        when {
            skill.osSkin != null && skill.osSkin == osFingerprint.osSkin -> score += 20f
            skill.osSkin == null                                          -> score += 8f
        }

        // Factor 10: Exact target API level match (10 pts)
        if (skill.targetApiLevel == osFingerprint.apiLevel) score += 10f

        return score
    }

    // ── Helpers ───────────────────────────────────────────────────

    /**
     * Extract search keywords from a natural language goal.
     * Removes stopwords, lowercases, deduplicates.
     */
    fun extractKeywords(goal: String): List<String> {
        val stopWords = setOf("the", "a", "an", "to", "for", "in", "on", "at",
                              "and", "or", "of", "with", "my", "me", "it", "i",
                              "please", "can", "you", "is", "are", "do", "does")
        return goal.lowercase()
            .split(" ", ",", ".", "!", "?")
            .map { it.trim() }
            .filter { it.length > 2 && it !in stopWords }
            .distinct()
            .take(8) // limit to avoid DB over-querying
    }

    private fun parseKeywordList(json: String): List<String> {
        return try {
            json.removePrefix("[").removeSuffix("]")
                .split(",")
                .map { it.trim().removeSurrounding("\"") }
                .filter { it.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun getInstalledAppVersion(packageName: String): String? {
        return try {
            context.packageManager
                .getPackageInfo(packageName, 0)
                .versionName
        } catch (_: PackageManager.NameNotFoundException) { null }
    }
}
