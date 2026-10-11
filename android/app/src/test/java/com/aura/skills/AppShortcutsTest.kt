package com.aura.skills

import com.aura.agent.ActionExecutor
import com.aura.data.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Guards `assets/app_shortcuts.json`. Phones download this file from `main`, so a bad
 * entry would reach every user without an app update — CI must catch it first.
 */
class AppShortcutsTest {

    private val file = SkillSeeder.parse(File("src/main/assets/app_shortcuts.json").readText())

    private val knownActions = ActionStep.KNOWN_ACTIONS

    @Test fun `file parses and has skills`() {
        assertTrue(file.version >= 1)
        assertTrue(file.skills.size >= 30)
    }

    @Test fun `identities are unique`() {
        val ids = file.skills.map { "${it.app}|${it.category}|${it.action}" }
        assertEquals(ids.groupBy { it }.filter { it.value.size > 1 }.keys.toString(), ids.size, ids.toSet().size)
    }

    @Test fun `every pattern is usable`() {
        for (s in file.skills) {
            assertTrue("${s.name}: no patterns", s.patterns.isNotEmpty())
            for (p in s.patterns) {
                assertTrue("${s.name}: unusable pattern \"$p\"", SkillMatcher.isUsable(p))
                assertTrue("${s.name}: derived slot in pattern \"$p\"",
                    SkillMatcher.slots(p).none { it in SkillMatcher.DERIVED_SLOTS })
            }
        }
    }

    @Test fun `every step slot comes from every pattern`() {
        for (s in file.skills) {
            val stepSlots = SkillMatcher.slotsInSteps(s.steps) - SkillMatcher.DERIVED_SLOTS
            for (p in s.patterns) {
                val missing = stepSlots - SkillMatcher.slots(p).toSet()
                assertTrue("${s.name}: pattern \"$p\" doesn't fill $missing", missing.isEmpty())
            }
        }
    }

    @Test fun `actions and risks are valid`() {
        for (s in file.skills) {
            assertTrue("${s.name}: bad risk ${s.risk}", s.risk in setOf("LOW", "MEDIUM", "HIGH", "CRITICAL"))
            assertTrue("${s.name}: no steps", s.steps.isNotEmpty())
            for (st in s.steps) assertTrue("${s.name}: unknown action ${st.action}", st.action in knownActions)
            // A risky skill must ask before it acts.
            if (s.risk == "HIGH" || s.risk == "CRITICAL") {
                assertTrue("${s.name}: HIGH risk without confirm_send/reply",
                    s.steps.any { it.action == ActionStep.CONFIRM_SEND || it.action == ActionStep.REPLY })
            }
        }
    }

    @Test fun `generic intents are on the executor's allow-list`() {
        for (s in file.skills) for (st in s.steps.filter { it.action == ActionStep.INTENT }) {
            assertNotNull("${s.name}: intent without intent_action", st.intentAction)
            val uri = st.uri?.let { android.net.Uri.parse(it.replace(Regex("\\{[a-z_]+\\}"), "x")) }
            // Uri.parse returns null in plain JVM tests; the action/mime checks still run.
            assertTrue("${s.name}: intent ${st.intentAction} not allowed",
                ActionExecutor.isAllowedIntent(st.intentAction!!, uri, st.mime))
        }
    }

    @Test fun `a sample goal fills each skill's steps`() {
        for (s in file.skills) {
            val p = SkillMatcher.normalize(s.patterns.first())
            val goal = SLOT.replace(p) { "sample ${it.groupValues[1].replace('_', ' ')}" }
            val params = SkillMatcher.match(goal, s.patterns)
            assertNotNull("${s.name}: \"$goal\" didn't match its own pattern", params)
            val withDerived = params!! + mapOf("contact_number" to "+911234567890", "contact_number_digits" to "911234567890")
            assertNotNull("${s.name}: steps left unfilled", SkillMatcher.fill(s.steps, withDerived))
        }
    }

    private companion object { val SLOT = Regex("\\{([a-z_]+)\\}") }
}
