package com.aura.llm

import com.aura.skills.SkillMatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerPromptTest {

    @Test fun `parses the full skill-aware reply`() {
        val raw = """
            {"intent":{"app":"com.whatsapp","category":"messaging","action":"send_message"},
             "params":{"contact_name":"Priya","message_text":"I'm on my way"},
             "patterns":["send {message_text} to {contact_name} on whatsapp"],
             "steps":[{"action":"open_app","pkg":"com.whatsapp"},
                      {"action":"tap","text":"{contact_name}"},
                      {"action":"type","input_text":"{message_text}"}]}
        """.trimIndent()
        val plan = PlannerPrompt.parsePlan(raw)
        assertEquals(3, plan.steps.size)
        assertEquals("com.whatsapp", plan.app)
        assertEquals("send_message", plan.action)
        assertEquals("Priya", plan.params["contact_name"])
        assertEquals(listOf("send {message_text} to {contact_name} on whatsapp"), plan.patterns)
        assertEquals("{message_text}", plan.steps[2].inputText)
    }

    @Test fun `accepts a bare array and markdown fences`() {
        val plan = PlannerPrompt.parsePlan("```json\n[{\"action\":\"OPEN_APP\",\"pkg\":\"com.google.android.youtube\"}]\n```")
        assertEquals(1, plan.steps.size)
        assertEquals("open_app", plan.steps[0].action)
        assertTrue(plan.params.isEmpty())
    }

    @Test fun `garbage gives an empty plan, not a crash`() {
        assertTrue(PlannerPrompt.parsePlan("sorry, I can't help with that").steps.isEmpty())
    }

    @Test fun `numbers in params become strings`() {
        val plan = PlannerPrompt.parsePlan("""{"params":{"amount":500},"steps":[{"action":"type","input_text":"{amount}"}]}""")
        assertEquals("500", plan.params["amount"])
    }

    @Test fun `plan values round-trip through fill and templatize`() {
        // What TaskManager does: run filled steps, save the template.
        val raw = """{"params":{"query":"lofi beats"},
            "steps":[{"action":"tap","text":"Search"},{"action":"type","input_text":"lofi beats"}]}"""
        val plan = PlannerPrompt.parsePlan(raw)
        val steps = SkillMatcher.fill(plan.steps, plan.params)!!
        assertEquals("lofi beats", steps[1].inputText)
        val template = SkillMatcher.templatize(steps, plan.params)
        assertEquals("{query}", template[1].inputText)
        assertEquals("Search", template[0].text)
    }

    @Test fun `recovery request lists done steps, the failure and the current screen`() {
        val msg = PlannerPrompt.continueMessage(
            goal = "send hi to priya on whatsapp",
            done = listOf(com.aura.data.model.ActionStep(action = "open_app", pkg = "com.whatsapp")),
            failed = com.aura.data.model.ActionStep(action = "tap", text = "Search"),
            error = "I couldn't find \"Search\" on the screen.",
            params = mapOf("contact_name" to "Priya"),
            uiContext = "[button] New chat #com.whatsapp:id/fab"
        )
        assertTrue(msg.contains("1. open_app pkg=com.whatsapp"))
        assertTrue(msg.contains("FAILED: tap text=\"Search\""))
        assertTrue(msg.contains("contact_name=\"Priya\""))
        assertTrue(msg.contains("[UI_CONTENT_START]\n[button] New chat"))
    }

    @Test fun `system prompt documents every action the executor supports`() {
        listOf("open_app", "tap", "type", "scroll", "swipe", "wait", "back", "home", "find_element",
            "read_text", "confirm_send", "verify", "clarify", "dial", "sms", "alarm", "timer", "open_link")
            .forEach { assertTrue("missing $it", PlannerPrompt.SYSTEM.contains("\"$it\"")) }
    }
}
