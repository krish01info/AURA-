// style example: a cleaned-up copy of android/app/src/main/java/com/aura/agent/TriggerMatcher.kt
package com.aura.agent

import com.aura.data.model.Trigger

// turns what the offline recognizer hears into live-task events. partial results repeat earlier
// words ("capture", then "capture capture"), so each spoken word fires only once.
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

    private data class Phrase(val tokens: List<String>, val triggerIndex: Int)

    private val phrases: List<Phrase> = buildPhrases(stopWords)

    // every word the recognizer should listen for (its grammar)
    val vocabulary: List<String> = phrases.map { it.tokens.joinToString(" ") }.distinct()

    // half of MIN_VALUE, so now - lastFired can't overflow
    private val lastFired = LongArray(triggers.size) { Long.MIN_VALUE / 2 }
    private val firedOnce = BooleanArray(triggers.size)

    // phrases already acted on in the current utterance; a final result starts a new one
    private var handledInUtterance = 0

    fun onHeard(text: String, isFinal: Boolean): List<Event> {
        val matches = match(tokenize(text))
        val fresh = matches.drop(handledInUtterance)
        handledInUtterance = if (isFinal) 0 else maxOf(handledInUtterance, matches.size)
        // counted as handled above, so words heard while AURA speaks never fire later
        if (fresh.isEmpty() || ignoreNow()) return emptyList()

        val now = clock()
        val events = mutableListOf<Event>()
        for (phrase in fresh) {
            val word = phrase.tokens.joinToString(" ")
            if (phrase.triggerIndex == STOP_WORD) {
                events += Event.Stop(word)
                break // nothing after a stop word matters
            }
            val index = phrase.triggerIndex
            val trigger = triggers[index]
            if (trigger.once && firedOnce[index]) continue
            if (now - lastFired[index] < trigger.cooldownMs) continue
            lastFired[index] = now
            firedOnce[index] = true
            events += Event.Fire(index, trigger, word)
        }
        return events
    }

    private fun buildPhrases(stopWords: List<String>): List<Phrase> {
        val all = mutableListOf<Phrase>()
        for (word in stopWords) {
            all += Phrase(tokenize(word), STOP_WORD)
        }
        for ((index, trigger) in triggers.withIndex()) {
            for (word in trigger.words) {
                all += Phrase(tokenize(word), index)
            }
        }
        // longest first, so "take photo" wins over "photo"
        return all.filter { it.tokens.isNotEmpty() }.sortedByDescending { it.tokens.size }
    }

    // phrases found in tokens, left to right; at each position the longest phrase wins
    private fun match(tokens: List<String>): List<Phrase> {
        val found = mutableListOf<Phrase>()
        var pos = 0
        while (pos < tokens.size) {
            val phrase = phrases.firstOrNull { startsAt(tokens, pos, it.tokens) }
            if (phrase != null) {
                found += phrase
                pos += phrase.tokens.size
            } else {
                pos++
            }
        }
        return found
    }

    private fun startsAt(tokens: List<String>, pos: Int, phrase: List<String>): Boolean {
        val end = pos + phrase.size
        return end <= tokens.size && tokens.subList(pos, end) == phrase
    }

    companion object {
        private const val STOP_WORD = -1

        // lowercase words without punctuation; vosk's "[unk]" (anything outside the grammar) is dropped
        fun tokenize(text: String): List<String> {
            val cleaned = text.lowercase()
                .replace("[unk]", " ")
                .replace(Regex("[^\\p{L}\\p{N}'\\s]"), " ")
            return cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }
        }
    }
}
