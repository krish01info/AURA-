package com.aura.agent

import com.aura.data.model.ActionStep
import com.aura.data.model.SkillPackEntity
import kotlin.math.ln

/**
 * VariantRanking — which stored skill variants may run on this phone, and which fits best.
 * Pure Kotlin (no Android APIs) so [SkillRouter]'s selection rules are unit-tested.
 */
object VariantRanking {

    /** The phone, as far as skill variants care. */
    data class Env(val apiLevel: Int, val osSkin: String?, val osClassTag: String, val deviceModel: String?)

    const val UNIVERSAL = "universal"
    /** Skin + Android group + app version all match — nothing more specific exists. */
    const val EXACT = 90.0

    private val SHORTCUT_ACTIONS = listOf(
        ActionStep.OPEN_LINK, ActionStep.DIAL, ActionStep.CALL, ActionStep.SMS, ActionStep.ALARM,
        ActionStep.TIMER, ActionStep.INTENT, ActionStep.MEDIA, ActionStep.REPLY
    )

    /** Can this variant run here at all? */
    fun isCompatible(skill: SkillPackEntity, env: Env): Boolean {
        if (skill.isStale || skill.steps.isBlank()) return false
        if (env.apiLevel < skill.minApiLevel) return false
        val max = skill.maxApiLevel
        if (max != null && env.apiLevel > max) return false
        if (skill.osSkin != null && skill.osSkin != env.osSkin) return false
        if (skill.osClassTag != UNIVERSAL && skill.osClassTag != env.osClassTag) return false
        return true
    }

    /**
     * Higher = made for an environment closer to this phone: skin (40) → Android group (30)
     * → app major version (20) → device (5), then success rate and usage as tie-breakers.
     */
    fun specificity(skill: SkillPackEntity, env: Env, installedAppMajor: String?): Double {
        var score = 0.0
        if (skill.osSkin != null && skill.osSkin == env.osSkin) score += 40
        if (skill.osClassTag == env.osClassTag) score += 30
        if (skill.appMajorVersion != null && skill.appMajorVersion == installedAppMajor) score += 20
        if (skill.deviceModel != null && skill.deviceModel == env.deviceModel) score += 5
        score += skill.successRate * 4
        score += ln(1.0 + skill.usageCount) * 0.3
        // Shortcut-based steps (intents/links) are more reliable than tapping through screens.
        if (SHORTCUT_ACTIONS.any { skill.steps.contains("\"$it\"") }) score += 1.0
        return score
    }
}
