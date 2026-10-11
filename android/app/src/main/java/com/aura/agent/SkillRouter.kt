package com.aura.agent

import android.util.Log
import com.aura.cloud.SupabaseSkillApi
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import com.aura.skills.OsFingerprint
import com.aura.skills.SkillCache
import com.aura.skills.SkillDiskCache
import com.aura.skills.SkillMatcher
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln

private const val TAG = "SkillRouter"

/**
 * A stored skill variant that fits the goal, with its steps already filled in.
 * @param spokenParams values taken from the user's words (used for learning)
 * @param params       spokenParams + contact details from [ContactResolver] (used to run)
 * @param question     set when a contact name is ambiguous — ask instead of running
 */
data class SkillMatch(
    val skill: SkillPackEntity,
    val spokenParams: Map<String, String>,
    val params: Map<String, String>,
    val steps: List<ActionStep>,
    val layer: String,
    val question: String? = null
)

/**
 * SkillRouter — finds a stored skill for a goal so no LLM call is needed.
 *
 * Lookup chain (first layer with a usable match wins):
 *   L1 RAM   (SkillCache, top ~20 most used)        → instant
 *   L2 disk  (SkillDiskCache, JSON + keyword index)  → promote to RAM
 *   L3 Room  (source of truth on the phone)          → write to disk + RAM
 *   L4 cloud (Supabase, only compatible variants)    → save to Room + disk + RAM
 *   none     → null: TaskManager plans with the LLM and SkillLearner saves the result
 *
 * A variant is usable only if:
 *   - it's compatible with this phone: not stale, Android version in range,
 *     same OEM skin (or "any"), same Android version group (or "universal");
 *   - one of its phrase patterns matches the goal (SkillMatcher), and
 *   - every `{slot}` in its steps gets a value from that match.
 * Among usable variants the most specific wins: skin → Android group → app major
 * version → device, then success rate. If a fast layer only has a generic variant,
 * Room is checked for a more specific variant of the same task.
 */
@Singleton
class SkillRouter @Inject constructor(
    private val skillCache: SkillCache,
    private val diskCache: SkillDiskCache,
    private val localDao: SkillPackDao,
    private val cloudApi: SupabaseSkillApi,
    private val os: OsFingerprint,
    private val contactResolver: ContactResolver
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val stepsSerializer = ListSerializer(ActionStep.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    suspend fun findSkill(goal: String): SkillMatch? {
        val keywords = SkillMatcher.keywords(goal)

        // ── L1: RAM ────────────────────────────────────────────────
        best(goal, skillCache.snapshot())?.let { m ->
            val final = preferMoreSpecific(goal, m)
            skillCache.recordHit(final.skill.skillId)
            if (final !== m) skillCache.promote(final.skill)
            return done(final, "ram")
        }
        if (keywords.isEmpty()) return null

        // ── L2: disk ───────────────────────────────────────────────
        best(goal, diskCache.findByKeywords(keywords))?.let { m ->
            val final = preferMoreSpecific(goal, m)
            skillCache.promote(final.skill)
            skillCache.flushEvictions()
            return done(final, "disk")
        }

        // ── L3: Room ───────────────────────────────────────────────
        val roomCandidates = keywords.flatMap { localDao.findByKeyword(it) }.distinctBy { it.skillId }
        best(goal, roomCandidates)?.let { m ->
            diskCache.put(m.skill)
            skillCache.promote(m.skill)
            skillCache.flushEvictions()
            return done(m, "room")
        }

        // ── L4: cloud (download) ──────────────────────────────────
        val cloudCandidates = cloudApi.searchSkills(keywords, os.osClassTag, os.osSkin).orEmpty()
        best(goal, cloudCandidates)?.let { m ->
            localDao.upsert(m.skill)
            diskCache.put(m.skill)
            skillCache.promote(m.skill)
            skillCache.flushEvictions()
            return done(m, "cloud")
        }

        Log.d(TAG, "No stored skill for \"$goal\" → LLM will plan")
        return null
    }

    private fun done(m: SkillMatch, layer: String): SkillMatch {
        Log.i(TAG, "Skill hit [$layer]: ${m.skill.displayName} (${m.skill.skillId}) params=${m.params.keys}")
        return m.copy(layer = layer)
    }

    // ──────────────────────────────────────────────────────────────
    // Selection
    // ──────────────────────────────────────────────────────────────

    private suspend fun best(goal: String, candidates: List<SkillPackEntity>): SkillMatch? {
        if (candidates.isEmpty()) return null
        val appMajor = mutableMapOf<String, String?>()
        val enriched = mutableMapOf<Map<String, String>, ContactResolver.Result>()
        val matches = mutableListOf<SkillMatch>()
        for (skill in candidates) {
            if (!isCompatible(skill)) continue
            matchSkill(goal, skill, enriched)?.let { matches += it }
        }
        return matches.maxByOrNull { specificity(it.skill, appMajor) }
    }

    private suspend fun matchSkill(
        goal: String,
        skill: SkillPackEntity,
        enrichCache: MutableMap<Map<String, String>, ContactResolver.Result>
    ): SkillMatch? {
        val patterns = decode(skill.phrasePatterns, stringsSerializer) ?: return null
        val spoken = SkillMatcher.match(goal, patterns) ?: return null
        val template = decode(skill.steps, stepsSerializer)?.takeIf { it.isNotEmpty() } ?: return null
        // Look up contacts once per distinct set of values (adds {contact_number}…)
        val resolved = enrichCache.getOrPut(spoken) { contactResolver.enrich(spoken) }
        if (resolved.question != null) {
            return SkillMatch(skill, spoken, resolved.params, emptyList(), "", resolved.question)
        }
        val filled = SkillMatcher.fill(template, resolved.params) ?: return null
        return SkillMatch(skill, spoken, resolved.params, filled, "")
    }

    /**
     * A RAM/disk hit may be a generic (e.g. universal) variant while Room holds a
     * variant made for this exact phone. Check the task's other variants once.
     */
    private suspend fun preferMoreSpecific(goal: String, hit: SkillMatch): SkillMatch {
        val appMajor = mutableMapOf<String, String?>()
        if (hit.question != null || hit.skill.intentId.isBlank() || specificity(hit.skill, appMajor) >= EXACT) return hit
        val siblings = localDao.getVariantsForIntent(hit.skill.intentId)
        val better = best(goal, siblings) ?: return hit
        return if (specificity(better.skill, appMajor) > specificity(hit.skill, appMajor)) better else hit
    }

    /** Can this variant run on this phone at all? */
    fun isCompatible(skill: SkillPackEntity): Boolean {
        if (skill.isStale || skill.steps.isBlank()) return false
        if (os.apiLevel < skill.minApiLevel) return false
        val max = skill.maxApiLevel
        if (max != null && os.apiLevel > max) return false
        if (skill.osSkin != null && skill.osSkin != os.osSkin) return false
        if (skill.osClassTag != UNIVERSAL && skill.osClassTag != os.osClassTag) return false
        return true
    }

    /** Higher = made for an environment closer to this phone. */
    private fun specificity(skill: SkillPackEntity, appMajorCache: MutableMap<String, String?>): Double {
        var score = 0.0
        if (skill.osSkin != null && skill.osSkin == os.osSkin) score += 40
        if (skill.osClassTag == os.osClassTag) score += 30
        val installedMajor = appMajorCache.getOrPut(skill.appPackage) { os.appMajorVersion(skill.appPackage) }
        if (skill.appMajorVersion != null && skill.appMajorVersion == installedMajor) score += 20
        if (skill.deviceModel != null && skill.deviceModel == os.deviceModel) score += 5
        score += skill.successRate * 4
        score += ln(1.0 + skill.usageCount) * 0.3
        // Shortcut-based steps (intents/links) are more reliable than tapping through screens.
        if (SHORTCUT_ACTIONS.any { skill.steps.contains("\"$it\"") }) score += 1.0
        return score
    }

    private fun <T> decode(raw: String, serializer: kotlinx.serialization.KSerializer<T>): T? = try {
        json.decodeFromString(serializer, raw)
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val UNIVERSAL = "universal"
        /** Skin + Android group + app version all match — nothing more specific exists. */
        const val EXACT = 90.0
        val SHORTCUT_ACTIONS = listOf(
            ActionStep.OPEN_LINK, ActionStep.DIAL, ActionStep.SMS, ActionStep.ALARM, ActionStep.TIMER
        )
    }
}
