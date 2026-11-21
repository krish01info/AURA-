// style example: a cleaned-up copy of android/app/src/test/java/com/aura/agent/TriggerMatcherTest.kt
package com.aura.agent

import com.aura.agent.TriggerMatcher.Event
import com.aura.data.model.ActionStep
import com.aura.data.model.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerMatcherTest {

    // a fake clock, so cooldowns are tested without waiting
    private var now = 10_000L
    private val tap = listOf(ActionStep(action = "tap", text = "Shutter"))
    private val start = Trigger(listOf("start"), tap, once = true)
    private val capture = Trigger(listOf("capture", "take photo"), tap)

    private fun matcher(ignore: () -> Boolean = { false }): TriggerMatcher =
        TriggerMatcher(listOf(start, capture), listOf("stop"), clock = { now }, ignoreNow = ignore)

    private fun fired(events: List<Event>): List<String> =
        events.filterIsInstance<Event.Fire>().map { it.word }

    @Test fun `a word fires once even though partials repeat it`() {
        val m = matcher()
        assertEquals(listOf("capture"), fired(m.onHeard("capture", isFinal = false)))
        assertEquals(emptyList<String>(), fired(m.onHeard("capture", isFinal = false)))
        assertEquals(emptyList<String>(), fired(m.onHeard("capture", isFinal = true)))
    }

    @Test fun `a second word in the same phrase fires`() {
        val m = matcher()
        assertEquals(listOf("start"), fired(m.onHeard("start", isFinal = false)))
        now += 2000
        assertEquals(listOf("capture"), fired(m.onHeard("start capture", isFinal = false)))
    }

    @Test fun `once triggers fire only the first time`() {
        val m = matcher()
        assertEquals(listOf("start"), fired(m.onHeard("start", isFinal = true)))
        now += 5000
        assertEquals(emptyList<String>(), fired(m.onHeard("start", isFinal = true)))
    }

    @Test fun `cooldown swallows quick repeats`() {
        val m = matcher()
        assertEquals(listOf("capture"), fired(m.onHeard("capture", isFinal = true)))
        now += 300
        assertEquals(emptyList<String>(), fired(m.onHeard("capture", isFinal = true)))
        now += 1000
        assertEquals(listOf("capture"), fired(m.onHeard("capture", isFinal = true)))
    }

    @Test fun `multi word phrases and unknown words`() {
        val m = matcher()
        assertEquals(listOf("take photo"), fired(m.onHeard("[unk] take photo", isFinal = true)))
        assertEquals(emptyList<String>(), fired(m.onHeard("[unk] [unk]", isFinal = true)))
    }

    @Test fun `stop word ends and nothing after it runs`() {
        val events = matcher().onHeard("stop capture", isFinal = true)
        assertEquals(1, events.size)
        assertTrue(events[0] is Event.Stop)
    }

    @Test fun `aura's own voice is ignored`() {
        var speaking = true
        val m = matcher { speaking }
        assertEquals(emptyList<String>(), fired(m.onHeard("capture", isFinal = true)))
        speaking = false
        now += 2000
        assertEquals(listOf("capture"), fired(m.onHeard("capture", isFinal = true)))
    }

    @Test fun `vocabulary lists every word`() {
        assertEquals(setOf("stop", "start", "capture", "take photo"), matcher().vocabulary.toSet())
    }
}
