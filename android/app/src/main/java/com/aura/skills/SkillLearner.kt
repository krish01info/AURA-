package com.aura.skills

import android.util.Log
import com.aura.cloud.SupabaseSkillApi
import com.aura.data.db.SkillPackDao
import com.aura.di.SupabaseModule
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import com.aura.llm.Plan
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillLearner"

/**
 * SkillLearner — saves a plan that just **succeeded** as a skill variant for this
 * phone, so the next time the same task is asked (in any wording it has learned)
 * it runs with no LLM call.
 *
 * What gets saved:
 *  - intent  = app + category + action (from the LLM, cleaned; heuristic fallback)
 *  - variant = intent + this phone's OEM skin + Android version group + the app's
 *              major version → one row per environment, never overwriting others
 *  - steps   = template with `{slot}`s (values come from the goal at run time)
 *  - phrase patterns = the goal with values replaced by slots, plus the LLM's
 *              alternatives that reproduce the same values (validated)
 *
 * If this phone's variant already exists, its steps are replaced (version + 1) when
 * the new plan differs — that's how a broken variant gets repaired. New phrasings
 * are shared with every variant of the same task, since wording isn't OS-specific.
 * Then the variant is written to Room, disk and RAM, and uploaded to the cloud.
 */
@Singleton
class SkillLearner @Inject constructor(
    private val skillPackDao: SkillPackDao,
    private val osFingerprint: OsFingerprint,
    private val cloudApi: SupabaseSkillApi,
    private val diskCache: SkillDiskCache,
    private val skillCache: SkillCache
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val stepsSerializer = ListSerializer(ActionStep.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    /**
     * @param goal     what the user said
     * @param plan     the LLM plan (intent, params, patterns)
     * @param template the executed steps with values replaced by `{slot}`s
     */
    /** Saves the run as a skill variant; returns its id, or null if it wasn't saved. */
    suspend fun learn(goal: String, plan: Plan, template: List<ActionStep>): String? {
        try {
            // Only values spoken by the user; app-derived ones (contact numbers) aren't in patterns.
            val params = plan.params.filterKeys { it !in SkillMatcher.DERIVED_SLOTS }
            val appPackage = plan.app?.trim()?.takeIf { it.contains('.') }
                ?: template.firstOrNull { it.action == ActionStep.OPEN_APP && !it.pkg.isNullOrBlank() }?.pkg
                ?: "unknown"
            if (template.any { it.action == ActionStep.CLARIFY }) return null

            // ── Phrase patterns (how to recognise this task next time) ──
            val patterns = (listOfNotNull(SkillMatcher.derivePattern(goal, params)) +
                SkillMatcher.validPatterns(goal, params, plan.patterns))
                .map { SkillMatcher.normalize(it) }
                .filter { SkillMatcher.isUsable(it) && SkillMatcher.slots(it).toSet() == params.keys }
                .distinct()
            if (patterns.isEmpty()) {
                Log.i(TAG, "Not saving \"$goal\": no safe phrase pattern (values can't be told apart)")
                return null
            }
            // Every slot used by the steps must be fillable from the patterns.
            val stepSlots = SkillMatcher.slotsInSteps(template)
            val fillable = params.keys + SkillMatcher.DERIVED_SLOTS
            if (!fillable.containsAll(stepSlots)) {
                Log.w(TAG, "Not saving \"$goal\": steps use unknown slots ${stepSlots - fillable}")
                return null
            }

            // ── Identity ─────────────────────────────────────────────
            val (heuristicCategory, heuristicAction) = SkillIdentity.inferIntent(goal, appPackage)
            val category = SkillIdentity.cleanToken(plan.category, heuristicCategory)
            val action = SkillIdentity.cleanToken(plan.action, heuristicAction)
            val intentId = SkillIdentity.generateCanonicalId(appPackage, category, action)

            val os = osFingerprint
            val appVersion = os.appVersion(appPackage)
            val appMajor = os.appMajorVersion(appPackage)
            val variantId = SkillIdentity.variantId(intentId, os.osSkin, os.osClassTag, appMajor)

            val siblings = skillPackDao.getVariantsForIntent(intentId)
            val allPatterns = (patterns + siblings.flatMap { decodeStrings(it.phrasePatterns) })
                .filter { SkillMatcher.isUsable(it) }
                .distinct()
                .take(MAX_PATTERNS)
            val stepsJson = json.encodeToString(stepsSerializer, template)
            val keywordsJson = json.encodeToString(stringsSerializer, SkillMatcher.patternKeywords(allPatterns))
            val patternsJson = json.encodeToString(stringsSerializer, allPatterns)
            val paramsJson = json.encodeToString(stringsSerializer, params.keys.sorted())

            val existing = siblings.firstOrNull { it.skillId == variantId }
            val variant = if (existing == null) {
                SkillPackEntity(
                    skillId = variantId,
                    intentId = intentId,
                    appPackage = appPackage,
                    intentCategory = category,
                    intentAction = action,
                    displayName = displayName(appPackage, category, action),
                    triggerKeywords = keywordsJson,
                    parameters = paramsJson,
                    phrasePatterns = patternsJson,
                    steps = stepsJson,
                    riskLevel = inferRiskLevel(template),
                    minApiLevel = classMinApi(os.osClassTag),
                    maxApiLevel = null,
                    targetApiLevel = os.apiLevel,
                    osClassTag = os.osClassTag,
                    osSkin = os.osSkin,
                    skinVersion = os.skinVersion,
                    deviceModel = os.deviceModel,
                    appVersion = appVersion,
                    appMajorVersion = appMajor,
                    usageCount = 1,
                    successCount = 1,
                    successRate = 1.0f,
                    createdBy = "local",
                    lastUsedAt = System.currentTimeMillis(),
                    description = "Learned from: $goal"
                )
            } else {
                val stepsChanged = existing.steps != stepsJson
                existing.copy(
                    steps = stepsJson,
                    phrasePatterns = patternsJson,
                    triggerKeywords = keywordsJson,
                    parameters = paramsJson,
                    riskLevel = inferRiskLevel(template),
                    appVersion = appVersion,
                    // New steps → new version with fresh counters (old stats were for the old steps)
                    version = if (stepsChanged) existing.version + 1 else existing.version,
                    usageCount = existing.usageCount + 1,
                    successCount = if (stepsChanged) 1 else existing.successCount + 1,
                    failureCount = if (stepsChanged) 0 else existing.failureCount,
                    successRate = if (stepsChanged) 1.0f
                        else (existing.successCount + 1f) / (existing.successCount + 1 + existing.failureCount),
                    isStale = false,
                    syncedAt = 0L,
                    lastUsedAt = System.currentTimeMillis()
                )
            }

            save(variant)
            Log.i(TAG, "✅ ${if (existing == null) "Learned" else "Updated"} skill $variantId " +
                "[$category/$action on ${os.osSkin}/${os.osClassTag}/app v$appMajor] — ${allPatterns.size} pattern(s)")

            // Share new phrasings with the task's other variants (wording isn't OS-specific).
            siblings.filter { it.skillId != variantId && decodeStrings(it.phrasePatterns).toSet() != allPatterns.toSet() }
                .forEach { sibling ->
                    save(sibling.copy(phrasePatterns = patternsJson, triggerKeywords = keywordsJson), upload = false)
                }
            return variantId
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to learn skill: ${e.message}", e)
            return null
        }
    }

    /**
     * Upload skills learned on this phone that never reached the cloud (offline, server
     * error, or cloud set up later). Called at app start; a no-op without cloud keys.
     */
    suspend fun uploadPending() {
        if (!SupabaseModule.isConfigured) return
        val pending = skillPackDao.getUnsynced().filter { !it.isStale }
        if (pending.isEmpty()) return
        var done = 0
        for (skill in pending) {
            if (cloudApi.uploadSkill(skill)) {
                skillPackDao.markSynced(skill.skillId)
                done++
            }
        }
        Log.i(TAG, "☁️ Uploaded $done of ${pending.size} pending skill(s)")
    }

    /** Write a variant to Room, disk and RAM, then (optionally) upload it. */
    private suspend fun save(variant: SkillPackEntity, upload: Boolean = true) {
        skillPackDao.upsert(variant)
        diskCache.put(variant)
        skillCache.promote(variant)
        skillCache.flushEvictions()
        if (upload && cloudApi.uploadSkill(variant)) {
            skillPackDao.markSynced(variant.skillId)
            Log.i(TAG, "☁️ Uploaded skill variant ${variant.skillId}")
        }
    }

    private fun decodeStrings(raw: String): List<String> = try {
        json.decodeFromString(stringsSerializer, raw)
    } catch (_: Exception) {
        emptyList()
    }

    private fun displayName(appPackage: String, category: String, action: String): String {
        val app = appPackage.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        return "$app: ${action.replace('_', ' ')}".take(60).ifBlank { "$app: $category" }
    }

    /** Lowest API level of an Android version group (the variant is valid across the group). */
    private fun classMinApi(osClassTag: String): Int = when (osClassTag) {
        "android_10" -> 29
        "android_11" -> 30
        "android_12_13" -> 31
        "android_14_plus" -> 34
        else -> 29
    }

    private fun inferRiskLevel(steps: List<ActionStep>): String = when {
        steps.any { it.action == ActionStep.CONFIRM_SEND && it.risk == "CRITICAL" } -> "CRITICAL"
        steps.any { it.action == ActionStep.CONFIRM_SEND } -> "HIGH"
        steps.any { it.action == ActionStep.TYPE || it.action == ActionStep.TAP } -> "MEDIUM"
        else -> "LOW"
    }

    private companion object {
        const val MAX_PATTERNS = 30
    }
}
