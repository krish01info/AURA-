package com.aura.policy

/**
 * Pulls a rupee amount out of text like "Pay ₹5,000 to Rahul", "send Rs. 250", "INR 1,200.50".
 *
 * Prefers a number that has a currency marker; falls back to a plain number only
 * when [allowPlainNumber] is true. Handles thousands separators (the old regex
 * read "₹5,000" as 5).
 */
object AmountParser {
    private val WITH_CURRENCY = Regex(
        "(?:₹|\\brs\\.?|\\binr)\\s*([0-9][0-9,]*(?:\\.[0-9]+)?)",
        RegexOption.IGNORE_CASE
    )
    private val PLAIN_NUMBER = Regex("\\b([0-9][0-9,]*(?:\\.[0-9]+)?)\\b")

    fun parse(text: String?, allowPlainNumber: Boolean = false): Double? {
        if (text.isNullOrBlank()) return null
        val match = WITH_CURRENCY.find(text)
            ?: if (allowPlainNumber) PLAIN_NUMBER.find(text) else null
        return match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }
}
