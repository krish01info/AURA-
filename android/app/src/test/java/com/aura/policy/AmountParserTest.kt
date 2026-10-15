package com.aura.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmountParserTest {

    @Test fun `thousands separators are read correctly`() {
        assertEquals(5000.0, AmountParser.parse("Pay ₹5,000 to Rahul?")!!, 0.0)
        assertEquals(125000.5, AmountParser.parse("Send Rs. 1,25,000.50")!!, 0.0)
        assertEquals(250.0, AmountParser.parse("INR 250 to Mom")!!, 0.0)
    }

    @Test fun `numbers without a currency are ignored unless allowed`() {
        assertNull(AmountParser.parse("Pay Rahul for 2 tickets"))
        assertEquals(2.0, AmountParser.parse("Pay Rahul for 2 tickets", allowPlainNumber = true)!!, 0.0)
    }

    @Test fun `the amount with a currency wins over other numbers`() {
        assertEquals(500.0, AmountParser.parse("Pay 2 bills of ₹500")!!, 0.0)
    }
}
