package com.aura.voice

import com.aura.voice.ApprovalParser.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalParserTest {

    @Test fun `plain yes and no`() {
        assertEquals(Answer.Yes, ApprovalParser.parse(listOf("yes")))
        assertEquals(Answer.Yes, ApprovalParser.parse(listOf("Okay go ahead")))
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("no")))
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("No, don't send it")))
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("nahi")))
        assertEquals(Answer.Yes, ApprovalParser.parse(listOf("haan")))
    }

    @Test fun `a no in a later guess still counts`() {
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("now", "know", "no")))
    }

    @Test fun `unclear answers give null`() {
        assertNull(ApprovalParser.parse(listOf("hmm", "what")))
        assertNull(ApprovalParser.parse(emptyList()))
    }

    @Test fun `no plus a command is a correction`() {
        assertEquals(Answer.No("call rohit"), ApprovalParser.parse(listOf("No, call Rohit instead")))
        assertEquals(Answer.No("send it to priya"), ApprovalParser.parse(listOf("no I said send it to Priya")))
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("no thanks")))
    }

    @Test fun `hindi words match whole words only`() {
        assertEquals(Answer.No(""), ApprovalParser.parse(listOf("नहीं")))
        assertEquals(Answer.Yes, ApprovalParser.parse(listOf("हाँ")))
        assertNull(ApprovalParser.parse(listOf("करना")))
    }

    @Test fun `bare no`() {
        assertTrue(ApprovalParser.isJustNo("No"))
        assertTrue(!ApprovalParser.isJustNo("no call mom"))
    }
}
