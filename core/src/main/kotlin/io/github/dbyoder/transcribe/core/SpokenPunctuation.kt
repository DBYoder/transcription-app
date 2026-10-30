package io.github.dbyoder.transcribe.core

/**
 * Punctuation said out loud: "Hi John comma how are you question mark" → "Hi John, how are you?"
 *
 * Each spoken mark attaches to the word before it and replaces whatever punctuation the speech
 * model guessed there, and the next word is re-cased to match (capital after . ? !, lowercase
 * after , ; :). "Period" and "colon" are also ordinary words, so they're left alone after words
 * like "the" or "trial" ("the trial period", "a period of time").
 */
object SpokenPunctuation {
    private enum class Attach { TO_PREVIOUS_WORD, TO_NEXT_WORD }

    private class Mark(
        val words: List<String>,
        val symbol: String,
        val attach: Attach = Attach.TO_PREVIOUS_WORD,
        /** Real punctuation replaces the model's guess; quotes and brackets go alongside it. */
        val replacesPunctuation: Boolean = true,
        val endsSentence: Boolean = false,
        /** Also an everyday noun, so it needs context to count as a command. */
        val alsoANoun: Boolean = false,
    )

    // Longer phrases first, so "semi colon" wins over "colon".
    private val MARKS = listOf(
        Mark(listOf("question", "mark"), "?", endsSentence = true),
        Mark(listOf("exclamation", "point"), "!", endsSentence = true),
        Mark(listOf("exclamation", "mark"), "!", endsSentence = true),
        Mark(listOf("full", "stop"), ".", endsSentence = true),
        Mark(listOf("semi", "colon"), ";"),
        Mark(listOf("open", "quote"), "\"", Attach.TO_NEXT_WORD, replacesPunctuation = false),
        Mark(listOf("begin", "quote"), "\"", Attach.TO_NEXT_WORD, replacesPunctuation = false),
        Mark(listOf("close", "quote"), "\"", replacesPunctuation = false),
        Mark(listOf("end", "quote"), "\"", replacesPunctuation = false),
        Mark(listOf("open", "parenthesis"), "(", Attach.TO_NEXT_WORD, replacesPunctuation = false),
        Mark(listOf("open", "paren"), "(", Attach.TO_NEXT_WORD, replacesPunctuation = false),
        Mark(listOf("close", "parenthesis"), ")", replacesPunctuation = false),
        Mark(listOf("close", "paren"), ")", replacesPunctuation = false),
        Mark(listOf("semicolon"), ";"),
        Mark(listOf("comma"), ","),
        Mark(listOf("ellipsis"), "..."),
        Mark(listOf("unquote"), "\"", replacesPunctuation = false),
        Mark(listOf("period"), ".", endsSentence = true, alsoANoun = true),
        Mark(listOf("colon"), ":", alsoANoun = true),
    )

    private val TRIGGERS = MARKS.map { it.words.first() }.toSet()

    // A word right before "period"/"colon" that means it's the noun ("the trial period", "my colon").
    private val NOUN_CUES = setOf(
        "a", "an", "the", "this", "that", "these", "those", "each", "every", "any", "same", "whole", "entire",
        "one", "another", "my", "your", "his", "her", "our", "their", "its", "first", "second", "third", "final",
        "last", "next", "short", "long", "brief", "extended", "given", "specific", "certain", "set", "fixed",
        "busy", "quiet", "slow", "tough", "hard", "difficult", "rough", "good", "great", "bad", "happy", "sad",
        "grace", "trial", "billing", "waiting", "cooling", "free", "reporting", "notice", "probation",
        "probationary", "holding", "study", "test", "school", "class", "lunch", "pay", "time", "transition",
        "transitional", "evaluation", "comment", "warranty", "rental", "open", "sigmoid", "transverse",
    )

    private val PUNCTUATION = ",.;:!?"
    private val WHITESPACE = Regex("\\s+")

    fun apply(text: String): String {
        val tokens = text.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val words = tokens.map { core(it).lowercase() }
        if (words.none { it in TRIGGERS }) return text.trim()

        val out = ArrayList<String>()
        var capitalizeNext = false
        var lowercaseNext = false
        var opening = "" // "open quote" / "open paren" waiting for the next word

        var i = 0
        while (i < tokens.size) {
            val mark = match(words, i, out.lastOrNull()?.let { core(it).lowercase() })
            if (mark != null) {
                if (mark.attach == Attach.TO_NEXT_WORD) {
                    opening += mark.symbol
                } else {
                    val prev = out.lastOrNull()
                    if (prev == null) {
                        out += mark.symbol // said on its own, e.g. to add "?" to text already typed
                    } else {
                        val base = if (mark.replacesPunctuation) prev.trimEnd { it in PUNCTUATION } else prev
                        out[out.lastIndex] = base + mark.symbol
                    }
                    if (mark.endsSentence) {
                        capitalizeNext = true
                        lowercaseNext = false
                    } else if (mark.replacesPunctuation) {
                        lowercaseNext = true
                        capitalizeNext = false
                    }
                }
                i += mark.words.size
                continue
            }

            var word = tokens[i]
            i++
            when {
                capitalizeNext -> word = capitalizeFirstLetter(word)
                lowercaseNext -> word = TextJoin.lowercaseEverydayFirstWord(word)
            }
            capitalizeNext = false
            lowercaseNext = false
            if (opening.isNotEmpty()) {
                word = opening + word
                opening = ""
            }
            out += word
        }
        if (opening.isNotEmpty()) out += opening
        return out.joinToString(" ")
    }

    private fun match(words: List<String>, index: Int, previousWord: String?): Mark? {
        for (mark in MARKS) {
            if (index + mark.words.size > words.size) continue
            if (mark.words.indices.any { words[index + it] != mark.words[it] }) continue
            if (mark.alsoANoun) {
                if (previousWord != null && previousWord in NOUN_CUES) continue
                if (words.getOrNull(index + 1) == "of") continue // "period of time"
            }
            return mark
        }
        return null
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '-'

    private fun core(token: String): String = token.trim { !isWordChar(it) }

    private fun capitalizeFirstLetter(word: String): String {
        val i = word.indexOfFirst { it.isLetter() }
        return if (i < 0) word else word.substring(0, i) + word[i].uppercaseChar() + word.substring(i + 1)
    }
}
