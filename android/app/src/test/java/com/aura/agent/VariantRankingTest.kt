package com.aura.agent

import com.aura.data.model.SkillPackEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VariantRankingTest {

    private val samsung14 = VariantRanking.Env(apiLevel = 34, osSkin = "oneui", osClassTag = "android_14_plus", deviceModel = "SM-S911B")

    private fun variant(
        skin: String? = null, osClass: String = "universal", min: Int = 29, max: Int? = null,
        appMajor: String? = null, device: String? = null, stale: Boolean = false,
        steps: String = """[{"action":"tap","text":"Send"}]""", rate: Float = 1f, uses: Int = 0
    ) = SkillPackEntity(
        skillId = "v", appPackage = "com.whatsapp", triggerKeywords = "[]", steps = steps,
        osSkin = skin, osClassTag = osClass, minApiLevel = min, maxApiLevel = max,
        appMajorVersion = appMajor, deviceModel = device, isStale = stale,
        successRate = rate, usageCount = uses
    )

    @Test fun `compatible variants`() {
        assertTrue(VariantRanking.isCompatible(variant(), samsung14))
        assertTrue(VariantRanking.isCompatible(variant(skin = "oneui", osClass = "android_14_plus"), samsung14))
    }

    @Test fun `incompatible variants are skipped`() {
        assertFalse(VariantRanking.isCompatible(variant(skin = "miui"), samsung14))
        assertFalse(VariantRanking.isCompatible(variant(osClass = "android_12_13"), samsung14))
        assertFalse(VariantRanking.isCompatible(variant(min = 35), samsung14))
        assertFalse(VariantRanking.isCompatible(variant(max = 33), samsung14))
        assertFalse(VariantRanking.isCompatible(variant(stale = true), samsung14))
        assertFalse(VariantRanking.isCompatible(variant(steps = ""), samsung14))
    }

    @Test fun `the variant made for this phone wins`() {
        val generic = VariantRanking.specificity(variant(), samsung14, "2")
        val sameSkin = VariantRanking.specificity(variant(skin = "oneui"), samsung14, "2")
        val exact = VariantRanking.specificity(variant(skin = "oneui", osClass = "android_14_plus", appMajor = "2"), samsung14, "2")
        assertTrue(sameSkin > generic)
        assertTrue(exact > sameSkin)
        assertTrue(exact >= VariantRanking.EXACT)
    }

    @Test fun `shortcut steps beat tapping when otherwise equal`() {
        val tapping = VariantRanking.specificity(variant(), samsung14, null)
        val shortcut = VariantRanking.specificity(variant(steps = """[{"action":"call","number":"1"}]"""), samsung14, null)
        assertTrue(shortcut > tapping)
    }

    @Test fun `a failing variant ranks below a reliable one`() {
        assertTrue(
            VariantRanking.specificity(variant(rate = 1f), samsung14, null) >
                VariantRanking.specificity(variant(rate = 0.4f), samsung14, null)
        )
    }
}
