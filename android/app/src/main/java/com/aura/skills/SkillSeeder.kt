package com.aura.skills

import android.content.Context
import android.util.Log
import com.aura.data.db.SkillPackDao
import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SkillSeeder"

/** The app shortcut file: `assets/app_shortcuts.json` (or a newer downloaded copy). */
@Serializable
data class ShortcutFile(
    val version: Int,
    val note: String? = null,
    val skills: List<ShortcutDef>
)

/** One built-in skill as written in app_shortcuts.json. */
@Serializable
data class ShortcutDef(
    val app: String,
    val category: String,
    val action: String,
    val name: String,
    val risk: String = "LOW",
    val description: String = "",
    val patterns: List<String>,
    val steps: List<ActionStep>
)

/**
 * SkillSeeder — built-in skills ("app shortcuts") from `app_shortcuts.json`.
 *
 * The list is data, not code: adding or fixing an app means editing
 * `android/app/src/main/assets/app_shortcuts.json`. Phones pick up the change from
 * GitHub within a day ([AppShortcutsUpdater]) — no app update needed.
 *
 * Each entry becomes a **universal** variant (any skin / Android / app version) with
 * `{slot}` templates and phrase patterns, so these tasks run with zero LLM calls. If one
 * fails on a phone, TaskManager re-plans and saves a phone-specific variant, which is
 * preferred from then on — built-in rows are never overwritten by learning.
 *
 * Sync rules (every launch, cheap): insert missing, refresh changed (keeping this
 * phone's counters), delete built-ins no longer in the file.
 */
@Singleton
class SkillSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillPackDao: SkillPackDao,
    private val skillCache: SkillCache
) {
    private val stepsSerializer = ListSerializer(ActionStep.serializer())
    private val stringsSerializer = ListSerializer(String.serializer())

    suspend fun seedIfEmpty() {
        val file = loadShortcuts() ?: return
        val wanted = file.skills.mapNotNull { def ->
            try { toEntity(def) } catch (e: Exception) {
                Log.w(TAG, "Skipping shortcut ${def.name}: ${e.message}"); null
            }
        }.associateBy { it.skillId }

        var added = 0
        var refreshed = 0
        var removed = 0
        for (seed in wanted.values) {
            val existing = skillPackDao.getSkill(seed.skillId)
            when {
                existing == null -> { skillPackDao.upsert(seed); added++ }
                existing.createdBy == BUILTIN && (existing.steps != seed.steps ||
                    existing.phrasePatterns != seed.phrasePatterns ||
                    existing.riskLevel != seed.riskLevel) -> {
                    skillPackDao.upsert(seed.copy(
                        usageCount = existing.usageCount,
                        successCount = existing.successCount,
                        failureCount = existing.failureCount,
                        successRate = existing.successRate,
                        lastUsedAt = existing.lastUsedAt,
                        version = existing.version + 1
                    ))
                    skillCache.invalidate(seed.skillId)
                    refreshed++
                }
            }
        }
        for (old in skillPackDao.getByCreator(BUILTIN)) {
            if (old.skillId !in wanted) {
                skillPackDao.delete(old.skillId)
                skillCache.invalidate(old.skillId)
                removed++
            }
        }
        if (added + refreshed + removed > 0) {
            Log.i(TAG, "✅ App shortcuts v${file.version}: $added added, $refreshed refreshed, $removed removed")
        }
    }

    /** The bundled file, or the downloaded copy when its version is newer. */
    private fun loadShortcuts(): ShortcutFile? {
        val bundled = try {
            context.assets.open(ASSET_NAME).bufferedReader().use { parse(it.readText()) }
        } catch (e: Exception) {
            Log.e(TAG, "Bundled $ASSET_NAME unreadable: ${e.message}"); null
        }
        val downloaded = try {
            downloadedFile(context).takeIf { it.exists() }?.readText()?.let { parse(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Downloaded $ASSET_NAME unreadable, ignoring: ${e.message}"); null
        }
        return listOfNotNull(bundled, downloaded).maxByOrNull { it.version }
    }

    private fun toEntity(def: ShortcutDef): SkillPackEntity {
        val intentId = SkillIdentity.generateCanonicalId(def.app, def.category, def.action)
        val normalized = def.patterns.map { SkillMatcher.normalize(it) }.filter { SkillMatcher.isUsable(it) }
        require(normalized.isNotEmpty()) { "no usable patterns" }
        require(def.steps.isNotEmpty()) { "no steps" }
        return SkillPackEntity(
            skillId = SkillIdentity.variantId(intentId, null, OS_UNIVERSAL, null),
            intentId = intentId,
            appPackage = def.app,
            intentCategory = def.category,
            intentAction = def.action,
            displayName = def.name,
            triggerKeywords = JSON.encodeToString(stringsSerializer, SkillMatcher.patternKeywords(normalized)),
            parameters = JSON.encodeToString(stringsSerializer, normalized.flatMap { SkillMatcher.slots(it) }.distinct()),
            phrasePatterns = JSON.encodeToString(stringsSerializer, normalized),
            steps = JSON.encodeToString(stepsSerializer, def.steps),
            riskLevel = def.risk,
            minApiLevel = 29, maxApiLevel = null, targetApiLevel = 29,
            osClassTag = OS_UNIVERSAL, osSkin = null, appMajorVersion = null,
            createdBy = BUILTIN,
            usageCount = 0, successCount = 0, failureCount = 0, successRate = 1.0f,
            description = def.description
        )
    }

    companion object {
        const val ASSET_NAME = "app_shortcuts.json"
        const val BUILTIN = "builtin"
        private const val OS_UNIVERSAL = "universal"

        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun parse(text: String): ShortcutFile = JSON.decodeFromString(ShortcutFile.serializer(), text)

        /** Where [AppShortcutsUpdater] stores a newer copy from GitHub. */
        fun downloadedFile(context: Context) = File(context.filesDir, ASSET_NAME)
    }
}
