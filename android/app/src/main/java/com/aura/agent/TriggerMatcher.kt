package com.aura.agent

import com.aura.data.model.Trigger

/**
 * TriggerMatcher — turns what the offline recognizer hears into live-task events.
 *
 * The recognizer reports a growing *partial* text while you speak ("capture", then
 * "capture capture") and a *final* text at the end of the phrase. Words are acted on as
 * soon as they appear in a partial (fast), and each spoken word fires only once
 * even though it shows up again in later partials and the final.
 *
 * Also: per-trigger cooldown (the same word twice within [Trigger.cooldownMs] counts once),
 * "once" triggers, multi-word phrases ("take photo"), and an [ignoreNow] hook so AURA's own
 * voice (TTS) isn't heard as a command. Pure Kotlin — unit-tested.
 */
class TriggerMatcher(
    private val triggers: List<Trigger>,
    stopWords: List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ignoreNow: () -> Boolean = { false }
) {
    sealed class Event {
        data class Fire(val index: Int, val trigger: Trigger, val word: String) : Event()
        data class Stop(val word: String) : Event()
    }

    private data class Phrase(val tokens: List<String>, val triggerIndex: Int) // -1 = stop word

    private val phrases: List<Phrase> = (
        stopWords.map { Phrase(tokenize(it), -1) } +
            triggers.flatMapIndexed { i, t -> t.words.map { Phrase(tokenize(it), i) } }
        ).filter { it.tokens.isNotEmpty() }
        // Longest first, so "take photo" wins over "photo".
        .sortedByDescending { it.tokens.size }

    /** All words the recognizer should listen for (its grammar). */
    val vocabulary: List<String> = phrases.map { it.tokens.joinToString(" ") }.distinct()

    private val lastFired = LongArray(triggers.size) { Long.MIN_VALUE / 2 }
    private val firedOnce = BooleanArray(triggers.size)
    /** Phrases already handled in the current utterance. */
    private var handledInUtterance = 0

    /** Feed one recognizer result; returns what to do, in order. */
    fun onHeard(text: String, isFinal: Boolean): List<Event> {
        val matches = match(tokenize(text))
        val fresh = if (matches.size > handledInUtterance) matches.drop(handledInUtterance) else emptyList()
        handledInUtterance = if (isFinal) 0 else maxOf(handledInUtterance, matches.size)
        if (fresh.isEmpty() || ignoreNow()) return emptyList()

        val now = clock()
        val events = mutableListOf<Event>()
        for (p in fresh) {
            val word = p.tokens.joinToString(" ")
            if (p.triggerIndex < 0) {
                events += Event.Stop(word)
                break // nothing after a stop word matters
            }
            val i = p.triggerIndex
            val t = triggers[i]
            if (t.once && firedOnce[i]) continue
            if (now - lastFired[i] < t.cooldownMs) continue
            lastFired[i] = now
            firedOnce[i] = true
            events += Event.Fire(i, t, word)
        }
        return events
    }

    /** Phrases found in [tokens], left to right, longest phrase first at each position. */
    private fun match(tokens: List<String>): List<Phrase> {
        val found = mutableListOf<Phrase>()
        var pos = 0
        while (pos < tokens.size) {
            val p = phrases.firstOrNull { ph ->
                pos + ph.tokens.size <= tokens.size && tokens.subList(pos, pos + ph.tokens.size) == ph.tokens
            }
            if (p != null) {
                found += p
                pos += p.tokens.size
            } else {
                pos++
            }
        }
        return found
    }

    companion object {
        /** Lowercase words without punctuation; "[unk]" (anything not in the grammar) is dropped. */
        fun tokenize(text: String): List<String> =
            text.lowercase()
                .replace("[unk]", " ")
                .replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ")
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
    }
}
