package com.aura.skills

import com.aura.data.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillMatcherTest {

    private val whatsappPatterns = listOf(
        "send {message_text} to {contact_name} on whatsapp",
        "message {contact_name} on whatsapp {message_text}"
    )

    @Test fun `normalize strips wake word, filler and punctuation`() {
        assertEquals("open youtube", SkillMatcher.normalize("Hey AURA, please open YouTube!"))
        assertEquals("open youtube", SkillMatcher.normalize("  can you open youtube please "))
        assertEquals("send {message_text} to {contact_name}", SkillMatcher.normalize("Send {message_text} to {contact_name}"))
    }

    @Test fun `match fills params and keeps the user's casing`() {
        val p = SkillMatcher.match("Hey AURA, send Hi there to Priya Sharma on WhatsApp", whatsappPatterns)
        assertNotNull(p)
        assertEquals("Hi there", p!!["message_text"])
        assertEquals("Priya Sharma", p["contact_name"])
    }

    @Test fun `match picks the pattern that fits`() {
        val p = SkillMatcher.match("message Rahul on WhatsApp I am on my way", whatsappPatterns)
        assertEquals(mapOf("contact_name" to "Rahul", "message_text" to "I am on my way"), p)
    }

    @Test fun `different app or intent does not match`() {
        assertNull(SkillMatcher.match("send hi to priya on telegram", whatsappPatterns))
        assertNull(SkillMatcher.match("open youtube", whatsappPatterns))
        assertNull(SkillMatcher.match("open youtube", listOf("open whatsapp")))
    }

    @Test fun `pattern without slots needs an exact phrase`() {
        assertEquals(emptyMap<String, String>(), SkillMatcher.match("Please open YouTube", listOf("open youtube")))
        assertNull(SkillMatcher.match("open youtube music", listOf("open youtube")))
    }

    @Test fun `adjacent slots are rejected because the split is ambiguous`() {
        assertNull(SkillMatcher.match("whatsapp priya sharma hello", listOf("whatsapp {contact_name} {message_text}")))
        assertTrue(!SkillMatcher.isUsable("whatsapp {contact_name} {message_text}"))
        assertTrue(SkillMatcher.isUsable("send {message_text} to {contact_name} on whatsapp"))
    }

    @Test fun `a pattern that is only slots never matches`() {
        assertNull(SkillMatcher.match("anything at all", listOf("{a} {b}")))
    }

    @Test fun `fill replaces slots and refuses leftovers`() {
        val template = listOf(
            ActionStep(action = "open_app", pkg = "com.whatsapp"),
            ActionStep(action = "tap", text = "{contact_name}"),
            ActionStep(action = "type", inputText = "{message_text}"),
            ActionStep(action = "confirm_send", message = "Send \"{message_text}\" to {contact_name}?", risk = "HIGH")
        )
        val filled = SkillMatcher.fill(template, mapOf("contact_name" to "Priya", "message_text" to "Hi"))
        assertNotNull(filled)
        assertEquals("Priya", filled!![1].text)
        assertEquals("Hi", filled[2].inputText)
        assertEquals("Send \"Hi\" to Priya?", filled[3].message)

        assertNull(SkillMatcher.fill(template, mapOf("contact_name" to "Priya")))
    }

    @Test fun `templatize replaces concrete values, longest first`() {
        val steps = listOf(
            ActionStep(action = "tap", text = "Priya Sharma"),
            ActionStep(action = "type", inputText = "see you at 7"),
            ActionStep(action = "confirm_send", message = "Send see you at 7 to Priya Sharma?")
        )
        val t = SkillMatcher.templatize(steps, mapOf("contact_name" to "Priya Sharma", "message_text" to "see you at 7"))
        assertEquals("{contact_name}", t[0].text)
        assertEquals("{message_text}", t[1].inputText)
        assertEquals("Send {message_text} to {contact_name}?", t[2].message)
    }

    @Test fun `derivePattern turns the goal into a reusable pattern`() {
        val pattern = SkillMatcher.derivePattern(
            "Hey AURA send hello to Amit on WhatsApp",
            mapOf("contact_name" to "Amit", "message_text" to "hello")
        )
        assertEquals("send {message_text} to {contact_name} on whatsapp", pattern)
        // And the derived pattern matches a new goal
        val p = SkillMatcher.match("send good night to Neha on whatsapp", listOf(pattern!!))
        assertEquals(mapOf("message_text" to "good night", "contact_name" to "Neha"), p)
    }

    @Test fun `derivePattern returns null when a value is not in the goal`() {
        assertNull(SkillMatcher.derivePattern("open youtube", mapOf("query" to "cats")))
    }

    @Test fun `validPatterns drops LLM patterns that don't reproduce the params`() {
        val params = mapOf("query" to "lofi beats")
        val valid = SkillMatcher.validPatterns(
            "play lofi beats on youtube", params,
            listOf("play {query} on youtube", "search {query} on youtube", "play {song} on youtube", "play {query}")
        )
        // "search …" doesn't match this goal; "{song}" has the wrong slot; "play {query}" gives the wrong value
        assertEquals(listOf("play {query} on youtube"), valid)
    }

    @Test fun `keywords skip stop words and slots`() {
        val kw = SkillMatcher.patternKeywords(listOf("send {message_text} to {contact_name} on whatsapp"))
        assertTrue(kw.containsAll(listOf("send", "whatsapp")))
        assertTrue(kw.none { it.contains("{") || it == "to" || it == "on" })
    }

    @Test fun `values in links are URL-encoded and turned back into slots`() {
        val template = listOf(ActionStep(action = "open_link",
            uri = "https://wa.me/{contact_number_digits}?text={message_text}"))
        val params = mapOf("contact_number_digits" to "919876543210", "message_text" to "hi there & bye")
        val filled = SkillMatcher.fill(template, params)!!
        assertEquals("https://wa.me/919876543210?text=hi%20there%20%26%20bye", filled[0].uri)
        val back = SkillMatcher.templatize(filled, params)
        assertEquals("https://wa.me/{contact_number_digits}?text={message_text}", back[0].uri)
    }

    @Test fun `slotsInSteps sees every text field`() {
        val steps = listOf(ActionStep(action = "dial", number = "{contact_number}"),
            ActionStep(action = "alarm", time = "{time}"))
        assertEquals(setOf("contact_number", "time"), SkillMatcher.slotsInSteps(steps))
    }

    @Test fun `fill and templatize cover intent extras`() {
        val template = listOf(ActionStep(action = "intent",
            intentAction = "android.media.action.MEDIA_PLAY_FROM_SEARCH", pkg = "com.spotify.music",
            extras = mapOf("query" to "{query}", "android.intent.extra.focus" to "vnd.android.cursor.item/*")))
        val filled = SkillMatcher.fill(template, mapOf("query" to "lofi beats"))!!
        assertEquals("lofi beats", filled[0].extras!!["query"])
        assertEquals("vnd.android.cursor.item/*", filled[0].extras!!["android.intent.extra.focus"])
        assertNull(SkillMatcher.fill(template, emptyMap()))
        assertEquals("{query}", SkillMatcher.templatize(filled, mapOf("query" to "lofi beats"))[0].extras!!["query"])
    }

    @Test fun `the more specific pattern scores higher`() {
        val goal = "play lofi beats on spotify"
        val generic = SkillMatcher.matchScored(goal, listOf("play {query}"))!!
        val spotify = SkillMatcher.matchScored(goal, listOf("play {query} on spotify"))!!
        assertEquals("lofi beats on spotify", generic.first["query"])
        assertEquals("lofi beats", spotify.first["query"])
        assertTrue(spotify.second > generic.second)
    }

    @Test fun `a slot never swallows a second command`() {
        val open = listOf("open {app_name}")
        assertEquals(mapOf("app_name" to "whatsapp"), SkillMatcher.match("open whatsapp", open))
        assertNull(SkillMatcher.match("open whatsapp and send hi to rahul", open))
        assertNull(SkillMatcher.match("open youtube then search lofi", open))
        assertNull(SkillMatcher.match("open youtube and then play lofi", open))
        // "and" inside a name/query is fine when no action follows it.
        assertEquals(mapOf("query" to "rock and roll"), SkillMatcher.match("play rock and roll", listOf("play {query}")))
    }

    @Test fun `a message may contain and plus a verb`() {
        val p = SkillMatcher.match("send I will come and call you later to Rahul on whatsapp", whatsappPatterns)
        assertEquals("I will come and call you later", p!!["message_text"])
    }

    @Test fun `on and in are interchangeable and the is optional`() {
        val play = listOf("find {query} on play store")
        assertEquals(mapOf("query" to "instagram"), SkillMatcher.match("find instagram in the play store", play))
        assertEquals(mapOf("query" to "instagram"), SkillMatcher.match("find instagram on Playstore", play))
        assertEquals(mapOf("query" to "zomato"), SkillMatcher.match("find zomato on google play store", play))
    }
}
