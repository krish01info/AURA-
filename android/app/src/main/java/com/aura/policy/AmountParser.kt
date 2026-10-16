package com.aura.policy

/**
 * Pulls a rupee amount out of text like "Pay ₹5,000 to Rahul", "send Rs. 250", "INR 1,200.50",
 * "500 rupees", "250 rs", "1,000/-".
 *
 * Prefers a number that has a currency marker (before or after it); falls back to a
 * plain number only when [allowPlainNumber] is true. Handles thousands separators
 * (the old regex read "₹5,000" as 5).
 */
object AmountParser {
    private const val NUMBER = "([0-9][0-9,]*(?:\\.[0-9]+)?)"
    private val CURRENCY_BEFORE = Regex(
        "(?:₹|\\brs\\.?|\\binr)\\s*$NUMBER",
        RegexOption.IGNORE_CASE
    )
    // Spoken amounts put the currency after the number: "send 500 rupees"
    private val CURRENCY_AFTER = Regex(
        "\\b$NUMBER\\s*(?:rupees?\\b|rs\\b|inr\\b|/-)",
        RegexOption.IGNORE_CASE
    )
    private val PLAIN_NUMBER = Regex("\\b$NUMBER\\b")

    fun parse(text: String?, allowPlainNumber: Boolean = false): Double? {
        if (text.isNullOrBlank()) return null
        val match = listOfNotNull(CURRENCY_BEFORE.find(text), CURRENCY_AFTER.find(text))
            .minByOrNull { it.range.first }
            ?: if (allowPlainNumber) PLAIN_NUMBER.find(text) else null
        return match?.groupValues?.get(1)?.replace(",", "")?.toDoubleOrNull()
    }
}
