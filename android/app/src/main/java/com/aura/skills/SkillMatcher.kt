package com.aura.skills

import com.aura.data.model.ActionStep

/**
 * SkillMatcher — matches a spoken/typed goal against a skill's phrase patterns
 * and fills the skill's step template, **without any API call**.
 *
 * A phrase pattern is the goal with its variable parts replaced by slots:
 *     "send {message_text} to {contact_name} on whatsapp"
 * Matching "Send hi there to Priya on WhatsApp" gives
 *     {message_text = "hi there", contact_name = "priya"}
 * and the template step `tap "{contact_name}"` becomes `tap "priya"`.
 *
 * Pure Kotlin (no Android APIs) so it is unit-tested on the JVM.
 */
object SkillMatcher {

    private val SLOT = Regex("\\{([a-z][a-z0-9_]*)\\}")

    /** Two slots with nothing but spaces between them can't be split reliably. */
    private val ADJACENT_SLOTS = Regex("\\}\\s*\\{")

    /** True if [pattern] is safe to use: has fixed words and no ambiguous adjacent slots. */
    fun isUsable(pattern: String): Boolean {
        val p = normalize(pattern)
        return literalLength(p) >= 3 && !ADJACENT_SLOTS.containsMatchIn(p)
    }

    /** Leading filler that never changes what the user wants. */
    private val LEADING_FILLER = Regex(
        "^(?:(?:hey|hi|ok|okay)\\s+aura|aura|please|pls|can you|could you|would you|will you|kindly|just)\\b\\s*",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_FILLER = Regex(
        "\\s*\\b(?:please|pls|for me|now|right now|thanks|thank you)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Slots filled by the app at run time, not taken from the user's words
     * (see agent/ContactResolver). Steps may use them; patterns never contain them.
     */
    val DERIVED_SLOTS = setOf("contact_number", "contact_number_digits")

    /** Words ignored when building keyword lists for candidate lookup. */
    val STOP_WORDS = setOf(
        "the", "a", "an", "to", "for", "in", "on", "at", "and", "or", "of", "with", "my",
        "me", "it", "i", "please", "can", "you", "is", "are", "do", "does", "that", "this",
        "from", "say", "saying", "aura", "hey"
    )

    /**
     * Lowercase, drop punctuation and filler words, collapse spaces.
     * Slots like `{contact_name}` survive so patterns can be normalized too.
     */
    fun normalize(text: String): String = clean(text).lowercase()

    /** [normalize] without lowercasing — used to keep the user's casing in values. */
    private fun clean(text: String): String {
        var s = text
            .replace(Regex("[‘’“”\"`]"), "")
            .replace(Regex("[^\\p{L}\\p{N}{}_@#+.\\s'-]"), " ")
            .replace(Regex("(?<![\\p{L}\\p{N}])[.'-]|[.'-](?![\\p{L}\\p{N}])"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        // Strip filler repeatedly ("hey aura please open youtube")
        while (true) {
            val next = s.replace(LEADING_FILLER, "").replace(TRAILING_FILLER, "").trim()
            if (next == s) break
            s = next
        }
        return s
    }

    /** Slot names used in a pattern, in order. */
    fun slots(pattern: String): List<String> = SLOT.findAll(pattern).map { it.groupValues[1] }.toList()

    /**
     * Try each pattern against [goal]. Returns the filled parameters for the most
     * specific matching pattern (most literal characters), or null if none match.
     */
    fun match(goal: String, patterns: List<String>): Map<String, String>? {
        val g = clean(goal)
        if (g.isEmpty()) return null
        return patterns
            .asSequence()
            .map { normalize(it) }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { literalLength(it) }
            .mapNotNull { pattern -> matchOne(g, pattern) }
            .firstOrNull()
    }

    /** [cleanedGoal] keeps the user's casing; matching itself is case-insensitive. */
    private fun matchOne(cleanedGoal: String, pattern: String): Map<String, String>? {
        val names = slots(pattern)
        // Needs fixed words (or it matches anything) and no "{a} {b}" (can't tell where a ends).
        if (literalLength(pattern) < 3 || ADJACENT_SLOTS.containsMatchIn(pattern)) return null
        val regex = buildString {
            append('^')
            var last = 0
            for (m in SLOT.findAll(pattern)) {
                append(Regex.escape(pattern.substring(last, m.range.first)))
                append("(.+?)")
                last = m.range.last + 1
            }
            append(Regex.escape(pattern.substring(last)))
            append('$')
        }.toRegex()
        val lower = cleanedGoal.lowercase()
        val m = regex.matchEntire(lower) ?: return null
        // Take values from the original-case text when lowercasing kept the length.
        val source = if (lower.length == cleanedGoal.length) cleanedGoal else lower
        val values = names.mapIndexed { i, name ->
            val range = m.groups[i + 1]!!.range
            name to source.substring(range.first, range.last + 1).trim()
        }
        if (values.any { it.second.isEmpty() }) return null
        // The same slot used twice must have the same value both times.
        val grouped = values.groupBy({ it.first }, { it.second })
        if (grouped.values.any { vs -> vs.map { it.lowercase() }.distinct().size > 1 }) return null
        return grouped.mapValues { it.value.first() }
    }

    private fun literalLength(pattern: String): Int =
        pattern.replace(SLOT, "").replace(" ", "").length

    /**
     * Replace `{slot}` in every text field of [steps] with [params].
     * Returns null if any slot is left unfilled (the skill can't be used safely).
     */
    fun fill(steps: List<ActionStep>, params: Map<String, String>): List<ActionStep>? {
        fun f(s: String?): String? = s?.let { text ->
            SLOT.replace(text) { m -> params[m.groupValues[1]] ?: m.value }
        }
        // Values placed inside a link must be URL-encoded ("hi there" → "hi%20there").
        fun u(s: String?): String? = s?.let { text ->
            SLOT.replace(text) { m -> params[m.groupValues[1]]?.let(::encode) ?: m.value }
        }
        val filled = steps.map { st ->
            st.copy(
                pkg = f(st.pkg), text = f(st.text), viewId = f(st.viewId),
                inputText = f(st.inputText), message = f(st.message),
                clarification = f(st.clarification),
                uri = u(st.uri), number = f(st.number), time = f(st.time)
            )
        }
        return if (slotsInSteps(filled).isNotEmpty()) null else filled
    }

    /** Slot names still present in the steps' text fields. */
    fun slotsInSteps(steps: List<ActionStep>): Set<String> =
        steps.flatMap { st -> textFields(st).flatMap { slots(it) } }.toSet()

    private fun textFields(st: ActionStep): List<String> =
        listOfNotNull(st.pkg, st.text, st.viewId, st.inputText, st.message, st.uri, st.number, st.time)

    /** URL-encode for use inside a link (spaces as %20, not +). */
    fun encode(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    /**
     * Turn concrete steps into a reusable template: every occurrence of a param
     * value becomes `{slot}`. Longest values first so "Priya Sharma" wins over "Priya".
     * Values shorter than 2 characters are left alone (too likely to collide).
     * Inside links the URL-encoded form of the value is replaced too.
     */
    fun templatize(steps: List<ActionStep>, params: Map<String, String>): List<ActionStep> {
        val ordered = params.entries
            .filter { it.value.trim().length >= 2 }
            .sortedByDescending { it.value.length }
        fun t(s: String?): String? {
            if (s == null) return null
            var out: String = s
            for ((name, value) in ordered) {
                out = out.replace(Regex(Regex.escape(value.trim()), RegexOption.IGNORE_CASE), "{$name}")
            }
            return out
        }
        fun tu(s: String?): String? {
            if (s == null) return null
            var out: String = s
            for ((name, value) in ordered) {
                for (form in listOf(encode(value.trim()), encode(value.trim()).replace("%20", "+"), value.trim())) {
                    out = out.replace(Regex(Regex.escape(form), RegexOption.IGNORE_CASE), "{$name}")
                }
            }
            return out
        }
        return steps.map { st ->
            st.copy(
                text = t(st.text), inputText = t(st.inputText), message = t(st.message),
                number = t(st.number), time = t(st.time), uri = tu(st.uri)
            )
        }
    }

    /**
     * Build a pattern from a goal and its params: the normalized goal with each
     * value replaced by its slot. Returns null if a value isn't in the goal.
     */
    fun derivePattern(goal: String, params: Map<String, String>): String? {
        var p = normalize(goal)
        if (p.isEmpty()) return null
        for ((name, value) in params.entries.sortedByDescending { it.value.length }) {
            val v = normalize(value)
            if (v.isEmpty()) continue
            val idx = p.indexOf(v)
            if (idx < 0) return null
            p = p.substring(0, idx) + "{$name}" + p.substring(idx + v.length)
        }
        return p
    }

    /**
     * Keep only patterns that match [goal] and give back exactly [params]
     * (protects against wrong patterns from the LLM).
     */
    fun validPatterns(goal: String, params: Map<String, String>, candidates: List<String>): List<String> {
        val expected = params.mapValues { normalize(it.value) }
        return candidates
            .map { normalize(it) }
            .filter { it.isNotEmpty() && isUsable(it) && slots(it).toSet() == params.keys }
            .filter { pattern ->
                val got = matchOne(clean(goal), pattern) ?: return@filter false
                got.mapValues { it.value.lowercase() } == expected
            }
            .distinct()
    }

    /** Words from a goal useful for looking up candidate skills. */
    fun keywords(text: String): List<String> =
        normalize(text).replace(SLOT, " ")
            .split(' ')
            .map { it.trim('.', '\'', '-') }
            .filter { it.length > 2 && it !in STOP_WORDS }
            .distinct()

    /** Fixed (non-slot) words of all patterns — stored as the skill's trigger keywords. */
    fun patternKeywords(patterns: List<String>): List<String> =
        patterns.flatMap { keywords(it) }.distinct()
}
