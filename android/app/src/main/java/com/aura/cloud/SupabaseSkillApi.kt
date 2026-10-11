package com.aura.cloud

import android.util.Log
import com.aura.data.model.SkillPackEntity
import com.aura.di.SupabaseModule
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "SupabaseSkillApi"
private const val TABLE = "skill_packs"

/**
 * SupabaseSkillApi — Kotlin client for the cloud skill_packs table.
 *
 * All methods are no-ops when [SupabaseModule.isConfigured] is false,
 * so the app works fully offline before credentials are set.
 *
 * Responsibilities:
 *  - [searchSkills]      → keyword-based community skill lookup (L4 in lookup chain)
 *  - [getLatestVersion]  → check if cloud has a newer version of a known skill
 *  - [uploadSkill]       → push a locally-learned skill to the community store
 *  - [getTopSkills]      → paginated browse for the Skill Store screen
 *  - [recordExecution]   → push anonymous telemetry row to skill_executions
 */
@Singleton
class SupabaseSkillApi @Inject constructor(
    private val supabase: SupabaseClient
) {

    // ─────────────────────────────────────────────────────────────
    // Cloud DTO — mirrors the Supabase skill_packs table columns
    // ─────────────────────────────────────────────────────────────

    @Serializable
    data class CloudSkill(
        @SerialName("skill_id")         val skillId: String,          // variant id
        @SerialName("intent_id")        val intentId: String = "",
        @SerialName("phrase_patterns")  val phrasePatterns: String = "[]",
        @SerialName("app_major_version") val appMajorVersion: String? = null,
        @SerialName("app_package")      val appPackage: String,
        @SerialName("intent_category")  val intentCategory: String = "unknown",
        @SerialName("intent_action")    val intentAction: String = "unknown",
        @SerialName("display_name")     val displayName: String = "",
        @SerialName("trigger_keywords") val triggerKeywords: String,
        @SerialName("parameters")       val parameters: String = "[]",
        @SerialName("steps")            val steps: String,
        @SerialName("risk_level")       val riskLevel: String = "MEDIUM",
        @SerialName("min_api_level")    val minApiLevel: Int = 29,
        @SerialName("max_api_level")    val maxApiLevel: Int? = null,
        @SerialName("target_api_level") val targetApiLevel: Int = 29,
        @SerialName("os_class_tag")     val osClassTag: String = "universal",
        @SerialName("os_skin")          val osSkin: String? = null,
        @SerialName("skin_version")     val skinVersion: String? = null,
        @SerialName("device_model")     val deviceModel: String? = null,
        @SerialName("app_version")      val appVersion: String? = null,
        @SerialName("version")          val version: Int = 1,
        @SerialName("usage_count")      val usageCount: Int = 0,
        @SerialName("success_count")    val successCount: Int = 0,
        @SerialName("failure_count")    val failureCount: Int = 0,
        @SerialName("success_rate")     val successRate: Float = 1.0f,
        @SerialName("created_by")       val createdBy: String = "community",
        @SerialName("is_verified")      val isVerified: Boolean = false,
        @SerialName("downloads")        val downloads: Int = 0,
        @SerialName("description")      val description: String = "",
        @SerialName("is_stale")         val isStale: Boolean = false,
        @SerialName("last_used_at")     val lastUsedAt: Long = 0L
    ) {
        /** Convert cloud DTO → local Room entity. */
        fun toEntity(): SkillPackEntity = SkillPackEntity(
            skillId        = skillId,
            intentId       = intentId,
            phrasePatterns = phrasePatterns,
            appMajorVersion = appMajorVersion,
            appPackage     = appPackage,
            intentCategory = intentCategory,
            intentAction   = intentAction,
            displayName    = displayName,
            triggerKeywords = triggerKeywords,
            parameters     = parameters,
            steps          = steps,
            riskLevel      = riskLevel,
            minApiLevel    = minApiLevel,
            maxApiLevel    = maxApiLevel,
            targetApiLevel = targetApiLevel,
            osClassTag     = osClassTag,
            osSkin         = osSkin,
            skinVersion    = skinVersion,
            deviceModel    = deviceModel,
            appVersion     = appVersion,
            version        = version,
            usageCount     = usageCount,
            successCount   = successCount,
            failureCount   = failureCount,
            successRate    = successRate,
            createdBy      = "community",
            isVerified     = isVerified,
            downloads      = downloads,
            description    = description,
            isStale        = false,
            lastUsedAt     = lastUsedAt,
            syncedAt       = System.currentTimeMillis()
        )
    }

    @Serializable
    private data class ExecutionEvent(
        @SerialName("skill_id")  val skillId: String,
        @SerialName("outcome")   val outcome: String,
        @SerialName("os_class")  val osClass: String,
        @SerialName("api_level") val apiLevel: Int?,
        @SerialName("os_skin")   val osSkin: String?
    )

    // ─────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────

    /**
     * Search community skill variants by keyword (L4 of the lookup chain).
     * Only variants usable on this phone are returned: same Android version group
     * or universal, and same OEM skin or "any" (null). Non-stale only.
     * Returns null on any error so callers fall through to the LLM.
     */
    suspend fun searchSkills(
        keywords: List<String>,
        osClassTag: String,
        osSkin: String?
    ): List<SkillPackEntity>? {
        if (!SupabaseModule.isConfigured || keywords.isEmpty()) return null
        return try {
            val results = supabase.postgrest[TABLE]
                .select {
                    filter {
                        or { keywords.forEach { kw -> ilike("trigger_keywords", "%$kw%") } }
                        eq("is_stale", false)
                        or {
                            eq("os_class_tag", osClassTag)
                            eq("os_class_tag", "universal")
                        }
                        or {
                            if (osSkin != null) eq("os_skin", osSkin)
                            exact("os_skin", null)
                        }
                    }
                    order("success_rate", Order.DESCENDING)
                    limit(25)
                }
                .decodeList<CloudSkill>()
            Log.d(TAG, "Cloud search(${keywords}) → ${results.size} results")
            results.map { it.toEntity() }
        } catch (e: Exception) {
            Log.e(TAG, "searchSkills failed: ${e.message}")
            null
        }
    }

    /**
     * Fetch the latest cloud version of a specific skill by its canonical ID.
     * Used by [SkillUpdater.checkForCloudUpdate] when a local skill is DEGRADED.
     *
     * Returns null if no cloud skill exists or cloud has same/older version.
     */
    suspend fun getLatestVersion(skillId: String, localVersion: Int): SkillPackEntity? {
        if (!SupabaseModule.isConfigured) return null
        return try {
            val result = supabase.postgrest[TABLE]
                .select {
                    filter {
                        eq("skill_id", skillId)
                        gt("version", localVersion)
                        eq("is_stale", false)
                    }
                    limit(1)
                }
                .decodeSingleOrNull<CloudSkill>()
            if (result != null) {
                Log.i(TAG, "Cloud update available for $skillId: v${result.version} > local v$localVersion")
            }
            result?.toEntity()
        } catch (e: Exception) {
            Log.e(TAG, "getLatestVersion failed for $skillId: ${e.message}")
            null
        }
    }

    /**
     * Upload a locally-learned skill to the community store.
     * Called by SkillLearner after a successful task if user has opted in.
     *
     * UPSERT on the variant id: each skin / Android group / app version keeps its
     * own row, so uploads from different phones never overwrite each other.
     */
    suspend fun uploadSkill(skill: SkillPackEntity): Boolean {
        if (!SupabaseModule.isConfigured) return false
        return try {
            val cloud = CloudSkill(
                skillId        = skill.skillId,
                intentId       = skill.intentId,
                phrasePatterns = skill.phrasePatterns,
                appMajorVersion = skill.appMajorVersion,
                appPackage     = skill.appPackage,
                intentCategory = skill.intentCategory,
                intentAction   = skill.intentAction,
                displayName    = skill.displayName,
                triggerKeywords = skill.triggerKeywords,
                parameters     = skill.parameters,
                steps          = skill.steps,
                riskLevel      = skill.riskLevel,
                minApiLevel    = skill.minApiLevel,
                maxApiLevel    = skill.maxApiLevel,
                targetApiLevel = skill.targetApiLevel,
                osClassTag     = skill.osClassTag,
                osSkin         = skill.osSkin,
                skinVersion    = skill.skinVersion,
                deviceModel    = skill.deviceModel,
                appVersion     = skill.appVersion,
                version        = skill.version,
                successCount   = skill.successCount,
                failureCount   = skill.failureCount,
                successRate    = skill.successRate,
                description    = skill.description
            )
            supabase.postgrest[TABLE].upsert(cloud) {
                onConflict = "skill_id"
            }
            Log.i(TAG, "Uploaded skill: ${skill.skillId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "uploadSkill failed for ${skill.skillId}: ${e.message}")
            false
        }
    }

    /**
     * Browse top community skills for the Skill Store screen.
     *
     * @param osClassTag  Filter to skills compatible with this device OS class
     * @param osSkin      Filter by OEM skin (null = include all)
     * @param page        0-indexed page number (20 results per page)
     */
    suspend fun getTopSkills(
        osClassTag: String,
        osSkin: String?,
        page: Int = 0
    ): List<SkillPackEntity> {
        if (!SupabaseModule.isConfigured) return emptyList()
        return try {
            val from = (page * 20).toLong()
            val to = ((page + 1) * 20 - 1).toLong()
            val results = supabase.postgrest[TABLE]
                .select {
                    filter {
                        or {
                            eq("os_class_tag", osClassTag)
                            eq("os_class_tag", "universal")
                        }
                        eq("is_stale", false)
                        if (osSkin != null) {
                            or {
                                eq("os_skin", osSkin)
                                exact("os_skin", null)
                            }
                        }
                    }
                    order("downloads", Order.DESCENDING)
                    order("success_rate", Order.DESCENDING)
                    range(from, to)
                }
                .decodeList<CloudSkill>()
            Log.d(TAG, "getTopSkills(os=$osClassTag, skin=$osSkin, page=$page) → ${results.size}")
            results.map { it.toEntity() }
        } catch (e: Exception) {
            Log.e(TAG, "getTopSkills failed: ${e.message}")
            emptyList()
        }
    }

    /**
     * Push an anonymous execution telemetry event.
     * Called after every skill execution (success or failure).
     * No PII — just skillId, outcome, and OS metadata.
     */
    suspend fun recordExecution(
        skillId: String,
        outcome: String,     // "success" | "failure" | "cancelled"
        osClass: String,
        apiLevel: Int?,
        osSkin: String?
    ) {
        if (!SupabaseModule.isConfigured) return
        try {
            supabase.postgrest["skill_executions"].insert(
                ExecutionEvent(skillId, outcome, osClass, apiLevel, osSkin)
            )
            Log.d(TAG, "Telemetry: $skillId → $outcome")
        } catch (e: Exception) {
            Log.w(TAG, "recordExecution failed (non-critical): ${e.message}")
        }
    }
}
