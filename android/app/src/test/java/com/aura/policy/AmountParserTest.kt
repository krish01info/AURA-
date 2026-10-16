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

    @Test fun `spoken amounts with the currency after the number`() {
        assertEquals(500.0, AmountParser.parse("send 500 rupees to rahul")!!, 0.0)
        assertEquals(1.0, AmountParser.parse("pay mom 1 rupee")!!, 0.0)
        assertEquals(250.0, AmountParser.parse("pay 250 rs for the cab")!!, 0.0)
        assertEquals(1000.0, AmountParser.parse("Pay 1,000/- to Priya")!!, 0.0)
        assertNull(AmountParser.parse("pay 2 friends for lunch"))
    }

    @Test fun `numbers without a currency are ignored unless allowed`() {
        assertNull(AmountParser.parse("Pay Rahul for 2 tickets"))
        assertEquals(2.0, AmountParser.parse("Pay Rahul for 2 tickets", allowPlainNumber = true)!!, 0.0)
    }

    @Test fun `the amount with a currency wins over other numbers`() {
        assertEquals(500.0, AmountParser.parse("Pay 2 bills of ₹500")!!, 0.0)
    }
}
