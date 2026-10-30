package io.github.dbyoder.transcribe.core

/**
 * Instant, rule-based tidy-up of dictated text: no AI, so it adds no delay.
 *
 * - Drops filler sounds ("um", "uh", "hmm"...), fixing the punctuation around them.
 * - Collapses stutters ("I I think" → "I think"), but keeps meaningful repeats
 *   ("that that", "no no").
 * - Drops comma-wrapped fillers (", you know,", ", like,", ", I mean,").
 * - Turns spoken punctuation ("comma", "question mark", "period"...) into the marks
 *   themselves; see [SpokenPunctuation].
 * - Turns spoken "new line" / "new paragraph" into line breaks when said on their own
 *   (so "a new line of credit" is left alone).
 *
 * It deliberately leaves everything else exactly as the speech model wrote it.
 */
object TextCleaner {
    // um, umm, uh, uhh, uhm, er, erm, ah, ahh, hm, hmm, hmmm... ("mm" is left alone: it's also millimetres.)
    private val FILLER = Regex("^(u+m+|u+h+m*|e+r+m+|er|a+h+|h+m+)$")

    // Words people repeat on purpose, so a doubled one isn't a stutter.
    private val REPEAT_OK = setOf(
        "that", "had", "is", "very", "really", "no", "yes", "yeah", "bye", "ha", "haha", "so", "well",
        "many", "much", "more", "now", "please", "go", "come", "wait", "ok", "okay", "oh", "hey", "hi", "knock",
    )

    // Openers whose comma stays when a filler after them is removed ("So, um, I" → "So, I").
    private val KEEPS_COMMA = setOf(
        "well", "so", "okay", "ok", "yes", "yeah", "no", "oh", "hi", "hey", "now", "anyway", "actually", "right", "sure",
    )

    private val TERMINALS = ".!?"
    private val WHITESPACE = Regex("\\s+")
    private val SPOKEN_BREAK = Regex("""(?i)(^|[.!?,;:])\s*new\s+(paragraph|line)(?:[.!?,;:]+|\s*$)""")
    private val COMMA_FILLER = Regex("""(?i)([\p{L}\p{N}']+),\s*(?:you know|i mean|like),\s*""")

    fun clean(dictated: String): String {
        val text = SpokenPunctuation.apply(dictated)
        val out = StringBuilder()
        var segmentStart = 0
        var segmentIndex = 0
        for (match in SPOKEN_BREAK.findAll(text)) {
            val before = text.substring(segmentStart, match.range.first) + match.groupValues[1]
            out.append(cleanSegment(before, capitalizeStart = segmentIndex > 0))
            out.append(if (match.groupValues[2].equals("paragraph", ignoreCase = true)) "\n\n" else "\n")
            segmentStart = match.range.last + 1
            segmentIndex++
        }
        out.append(cleanSegment(text.substring(segmentStart), capitalizeStart = segmentIndex > 0))
        return out.toString().trim(' ')
    }

    private fun cleanSegment(segment: String, capitalizeStart: Boolean): String {
        val kept = ArrayList<String>()
        var capitalizeNext = capitalizeStart
        var droppedLeadingFiller = false

        for (token in segment.trim().split(WHITESPACE)) {
            if (token.isEmpty()) continue
            val core = core(token)
            val lower = core.lowercase()
            val trailing = trailing(token)

            if (FILLER.matches(lower)) {
                if (kept.isEmpty()) droppedLeadingFiller = true
                if (dropFiller(kept, trailing)) capitalizeNext = true
                continue
            }

            val prev = kept.lastOrNull()
            if (prev != null && lower.isNotEmpty() && lower !in REPEAT_OK) {
                val prevTrailing = trailing(prev)
                if (core(prev).lowercase() == lower && (prevTrailing.isEmpty() || prevTrailing == ",")) {
                    kept[kept.lastIndex] = prev.dropLast(prevTrailing.length) + trailing
                    continue
                }
            }

            var word = if (core == "i") token.replaceFirst("i", "I") else token
            if (capitalizeNext) {
                word = capitalizeFirstLetter(word)
                capitalizeNext = false
            }
            kept += word
        }

        var result = kept.joinToString(" ")
        result = COMMA_FILLER.replace(result) { m ->
            val word = m.groupValues[1]
            if (word.lowercase() in KEEPS_COMMA) "$word, " else "$word "
        }
        // Punctuation left dangling by a removed opening filler goes; a spoken "comma" on its own stays.
        return if (droppedLeadingFiller) result.trimStart(',', ';', ':', ' ') else result.trimStart(' ')
    }

    /**
     * Fixes punctuation around a removed filler. Returns true when the next word starts a
     * sentence (so it should be capitalized).
     */
    private fun dropFiller(kept: MutableList<String>, fillerTrailing: String): Boolean {
        val prev = kept.lastOrNull() ?: return true
        val terminal = fillerTrailing.firstOrNull { it in TERMINALS }
        if (terminal != null) {
            // "That's all, um." → "That's all."
            val trimmed = prev.trimEnd(',', ';', ':')
            kept[kept.lastIndex] = if (trimmed.lastOrNull()?.let { it in TERMINALS } == true) trimmed else trimmed + terminal
            return true
        }
        if (fillerTrailing.startsWith(",") && trailing(prev) == "," && core(prev).lowercase() !in KEEPS_COMMA) {
            // "I think, uh, we" → "I think we"
            kept[kept.lastIndex] = prev.dropLast(1)
        }
        return prev.lastOrNull()?.let { it in TERMINALS } == true
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '-'

    private fun core(token: String): String = token.trim { !isWordChar(it) }

    private fun trailing(token: String): String = token.takeLastWhile { !isWordChar(it) }

    private fun capitalizeFirstLetter(word: String): String {
        val i = word.indexOfFirst { it.isLetter() }
        return if (i < 0) word else word.substring(0, i) + word[i].uppercaseChar() + word.substring(i + 1)
    }
}
