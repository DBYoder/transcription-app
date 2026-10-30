package io.github.dbyoder.transcribe.core

/** Fits dictated text into whatever is already in a text box. */
object TextJoin {
    // Characters that attach to the word before them, so no space goes in front.
    private const val CLOSING = ".,!?;:)]}%"
    private const val SENTENCE_END = ".!?"

    /**
     * Everyday words. The speech model capitalizes the first word of every recording, so when
     * one of these starts a dictation dropped into the middle of a sentence, it was only
     * capitalized for that reason. Anything not listed (names, "I", acronyms) keeps its capital.
     */
    private val EVERYDAY_WORDS = setOf(
        "a", "about", "above", "actually", "after", "again", "all", "almost", "also", "although", "always",
        "an", "and", "another", "any", "anyone", "anything", "anyway", "are", "around", "as", "at", "back",
        "basically", "be", "because", "been", "before", "being", "below", "best", "better", "between", "both",
        "but", "by", "can", "can't", "could", "couldn't", "did", "didn't", "do", "does", "doesn't", "doing",
        "don't", "done", "down", "during", "each", "either", "else", "even", "every", "everyone", "everything",
        "few", "finally", "first", "for", "from", "get", "gets", "getting", "give", "go", "goes", "going",
        "gone", "good", "got", "great", "had", "has", "have", "having", "he", "he's", "her", "here", "here's",
        "hers", "him", "his", "how", "however", "if", "in", "into", "is", "isn't", "it", "it's", "its", "just",
        "kind", "last", "later", "least", "less", "let", "let's", "like", "likely", "little", "look", "lot",
        "make", "many", "maybe", "me", "might", "more", "most", "much", "must", "my", "need", "needs", "never",
        "next", "no", "nor", "not", "nothing", "now", "of", "off", "often", "ok", "okay", "on", "once", "one",
        "only", "or", "other", "otherwise", "our", "ours", "out", "over", "perhaps", "please", "probably",
        "quite", "rather", "really", "right", "same", "see", "she", "she's", "should", "shouldn't", "since",
        "so", "some", "someone", "something", "sometimes", "soon", "still", "such", "sure", "take", "than",
        "thanks", "that", "that's", "the", "their", "theirs", "them", "then", "there", "there's", "these",
        "they", "they're", "thing", "things", "think", "this", "those", "though", "through", "to", "today",
        "together", "tomorrow", "tonight", "too", "under", "unless", "until", "up", "us", "usually", "very",
        "was", "wasn't", "way", "we", "we'll", "we're", "we've", "well", "were", "what", "what's", "whatever",
        "when", "where", "whether", "which", "while", "who", "whoever", "whole", "why", "will", "with",
        "within", "without", "won't", "would", "wouldn't", "yeah", "yes", "yesterday", "yet", "you", "you'll",
        "you're", "you've", "your", "yours",
    )

    /**
     * Returns [text] adjusted for insertion between [before] (the text left of the cursor)
     * and [after] (the text right of it):
     * - trimmed, with a space added where words would otherwise run together;
     * - first word lowercased when it lands mid-sentence (if it's an everyday word), or
     *   capitalized when it starts a new sentence after existing text;
     * - the speech model's closing period dropped when the sentence carries on after the cursor.
     */
    fun forInsertion(text: String, before: String, after: String): String {
        var trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

        // Casing of the first word, judged by what's already before the cursor.
        val lastBefore = before.trimEnd(' ', '\t').lastOrNull()
        when {
            lastBefore == null -> Unit // empty box: leave the model's casing alone
            lastBefore == '\n' || lastBefore in SENTENCE_END -> trimmed = capitalizeFirst(trimmed)
            else -> trimmed = lowercaseEverydayFirstWord(trimmed)
        }

        // Drop the model's closing period if the sentence continues after the cursor.
        val nextAfter = after.trimStart(' ', '\t').firstOrNull()
        if (nextAfter != null && (nextAfter.isLetterOrDigit() || nextAfter in CLOSING) &&
            trimmed.endsWith('.') && !trimmed.endsWith("..")
        ) {
            trimmed = trimmed.dropLast(1)
        }

        val prev = before.lastOrNull()
        val leadingSpace = prev != null &&
            (prev.isLetterOrDigit() || prev in CLOSING) &&
            trimmed.first() !in CLOSING

        val next = after.firstOrNull()
        val trailingSpace = next != null && next.isLetterOrDigit()

        return buildString {
            if (leadingSpace) append(' ')
            append(trimmed)
            if (trailingSpace) append(' ')
        }
    }

    /**
     * Gives an AI rewrite of a dictated fragment the same edges as the [original] it replaces:
     * lowercase start if the original started lowercase mid-sentence, and no closing period if
     * the original had none. Multi-line rewrites are blocks of their own and are left as they are.
     */
    fun matchEdges(rewritten: String, original: String): String {
        var result = rewritten.trim()
        val source = original.trim()
        if (result.isEmpty() || source.isEmpty() || '\n' in result) return result
        if (source.firstOrNull { it.isLetter() }?.isLowerCase() == true) result = lowercaseEverydayFirstWord(result)
        if (!source.endsWith('.') && result.endsWith('.') && !result.endsWith("..")) {
            result = result.dropLast(1)
        }
        return result
    }

    private fun lowercaseEverydayFirstWord(text: String): String {
        val word = text.takeWhile { it.isLetter() || it == '\'' }
        if (word.length < 1 || !word[0].isUpperCase()) return text
        if (word.drop(1).any { it.isUpperCase() }) return text // "NASA", "iPhone"-style words keep their casing
        if (word.lowercase() !in EVERYDAY_WORDS) return text
        return text[0].lowercaseChar() + text.substring(1)
    }

    private fun capitalizeFirst(text: String): String {
        val i = text.indexOfFirst { it.isLetter() }
        if (i < 0 || text[i].isUpperCase()) return text
        return text.substring(0, i) + text[i].uppercaseChar() + text.substring(i + 1)
    }
}
