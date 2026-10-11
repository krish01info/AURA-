package com.aura.voice

import java.util.Locale

/**
 * ApprovalParser — reads a spoken answer to "Allow this?" (yes / no / "no, call Rohit instead").
 * Pure Kotlin so it's unit-tested on the JVM.
 */
object ApprovalParser {

    sealed class Answer {
        data object Yes : Answer()
        /** [correction] = what to do instead if they said it ("no, call Rohit"), else "". */
        data class No(val correction: String) : Answer()
    }

    /**
     * [guesses] are the recognizer's alternatives, best first. The first one that is clearly
     * a yes or a no wins — a short "no" is often only in the 2nd/3rd guess. null = unclear.
     */
    fun parse(guesses: List<String>): Answer? {
        for (raw in guesses) {
            val text = raw.lowercase(Locale.ROOT).trim()
            val words = text.split(Regex("[\\s,.!?]+")).filter { it.isNotBlank() }
            val no = NO_REGEX.find(text)
            if (no != null || words.any { it in NO_HI }) {
                // "no, call Rohit instead" → run "call Rohit"; "no don't send it" → just no.
                val rest = (no?.let { text.substring(it.range.last + 1) } ?: "")
                    .trim(' ', ',', '.', '!')
                    .replace(CORRECTION_FILLER, "")
                    .trim(' ', ',', '.', '!')
                val isCommand = COMMAND_START.containsMatchIn(rest) && !NO_REGEX.containsMatchIn(rest)
                return Answer.No(if (isCommand) rest else "")
            }
            if (YES_REGEX.containsMatchIn(text) || words.any { it in YES_HI }) return Answer.Yes
        }
        return null
    }

    /** True for a bare "no" / "cancel" with nothing else. */
    fun isJustNo(text: String) = NO_REGEX.matches(text.lowercase(Locale.ROOT).trim())

    private val NO_REGEX =
        Regex("\\b(no|nope|nah|na|cancel|deny|stop|don'?t|do not|wrong|not that|nahi|nahin|mat|galat)\\b")
    private val YES_REGEX = Regex(
        "\\b(yes|yeah|yep|yup|yah|ya|confirm|allow|ok|okay|sure|go ahead|do it|send it|haan|han|ha|ji|theek hai|thik hai)\\b"
    )

    /** Devanagari answers, matched as whole words (\\b doesn't work with Devanagari). */
    private val NO_HI = setOf("नहीं", "नही", "ना", "मत", "गलत")
    private val YES_HI = setOf("हाँ", "हां", "हा", "जी", "ठीक")

    /** A correction must start like a command: "call Rohit", "send it to Priya"… */
    private val COMMAND_START = Regex(
        "^(call|phone|ring|dial|send|message|text|whatsapp|open|search|find|play|reply|tell|set|book|order|go|show|take|turn|pay)\\b"
    )
    private val CORRECTION_FILLER = Regex("^(i said|i meant|actually|instead)\\s*|\\s*\\binstead$")
}
