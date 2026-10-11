package com.aura.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TimeParserTest {

    @Test fun `clock times`() {
        assertEquals(7 to 30, TimeParser.parseClock("07:30"))
        assertEquals(19 to 5, TimeParser.parseClock("19.05"))
        assertEquals(7 to 0, TimeParser.parseClock("7 am"))
        assertEquals(19 to 30, TimeParser.parseClock("7:30 pm"))
        assertEquals(0 to 15, TimeParser.parseClock("12:15 am"))
        assertEquals(12 to 0, TimeParser.parseClock("12 pm"))
        assertEquals(6 to 45, TimeParser.parseClock("6:45 a.m."))
        assertNull(TimeParser.parseClock("25:00"))
        assertNull(TimeParser.parseClock("tomorrow"))
    }

    @Test fun `durations in seconds`() {
        assertEquals(300, TimeParser.parseDuration("300"))
        assertEquals(300, TimeParser.parseDuration("5 minutes"))
        assertEquals(300, TimeParser.parseDuration("5m"))
        assertEquals(5400, TimeParser.parseDuration("1 hour 30 minutes"))
        assertEquals(90, TimeParser.parseDuration("90 sec"))
        assertEquals(5400, TimeParser.parseDuration("1.5 hours"))
        assertEquals(300, TimeParser.parseDuration("05:00"))
        assertEquals(3900, TimeParser.parseDuration("1:05:00"))
        assertNull(TimeParser.parseDuration("a while"))
    }
}
