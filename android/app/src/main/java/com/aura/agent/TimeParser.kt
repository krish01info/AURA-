package com.aura.agent

/**
 * TimeParser — turns spoken times into numbers for the alarm / timer shortcuts.
 * Pure Kotlin, unit-tested.
 */
object TimeParser {

    private val CLOCK = Regex(
        "^\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.?|p\\.m\\.?)?\\s*$",
        RegexOption.IGNORE_CASE
    )
    private val DURATION_PART = Regex(
        "(\\d+(?:\\.\\d+)?)\\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\\b",
        RegexOption.IGNORE_CASE
    )

    /** "07:30", "7:30 pm", "7 am", "19.05", "7" → (hour 0-23, minute), or null. */
    fun parseClock(time: String?): Pair<Int, Int>? {
        val m = CLOCK.find(time ?: return null) ?: return null
        var h = m.groupValues[1].toInt()
        val min = m.groupValues[2].ifEmpty { "0" }.toInt()
        val suffix = m.groupValues[3].lowercase()
        if (suffix.startsWith("p") && h in 1..11) h += 12
        if (suffix.startsWith("a") && h == 12) h = 0
        if (suffix.isNotEmpty() && h !in 0..23) return null
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    /**
     * Length in seconds from "300", "05:00", "1:05:00", "5m", "5 minutes",
     * "1 hour 30 minutes", "90 sec", "1.5 hours". Null if it can't be read.
     */
    fun parseDuration(time: String?): Int? {
        val t = time?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        t.toIntOrNull()?.let { return it }

        val parts = DURATION_PART.findAll(t).toList()
        if (parts.isNotEmpty()) {
            val total = parts.sumOf { p ->
                val n = p.groupValues[1].toDouble()
                when (p.groupValues[2].first()) {
                    'h' -> n * 3600
                    'm' -> n * 60
                    else -> n
                }
            }
            return total.toInt().takeIf { it > 0 }
        }

        val colon = t.split(':').map { it.trim().toIntOrNull() ?: return null }
        return when (colon.size) {
            2 -> colon[0] * 60 + colon[1]
            3 -> colon[0] * 3600 + colon[1] * 60 + colon[2]
            else -> null
        }
    }
}
