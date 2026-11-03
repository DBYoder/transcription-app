package io.github.dbyoder.transcribe.core

/**
 * A word or name you use, spelled your way.
 *
 * @param word the right spelling ("Wispr Flow", "Saoirse", "iPhone").
 * @param heardAs what the speech model tends to write instead ("whisper flow", "seersha").
 */
data class DictionaryEntry(val word: String, val heardAs: List<String> = emptyList())

/** Fixes the spelling of your own words and names in dictated text. */
object PersonalDictionary {
    private const val BOUNDARY_BEFORE = """(?<![\p{L}\p{N}])"""
    private const val BOUNDARY_AFTER = """(?![\p{L}\p{N}])"""

    /**
     * Replaces everything each entry is [DictionaryEntry.heardAs] with its word, as whole words
     * and ignoring case. Words with unusual capitals ("iPhone", "DBYoder") and multi-word names
     * also get their capitals fixed. A plain capitalised word like "Will" doesn't, so the
     * everyday "will" is left alone.
     */
    fun apply(text: String, entries: List<DictionaryEntry>): String {
        if (entries.isEmpty() || text.isBlank()) return text
        val rules = LinkedHashMap<String, String>() // phrase key -> word
        for (entry in entries) {
            val word = entry.word.trim()
            if (word.isEmpty()) continue
            for (heard in entry.heardAs) {
                val key = Phrase.key(heard)
                if (key.isNotEmpty()) rules[key] = word
            }
            if (fixesCapitals(word)) rules.putIfAbsent(Phrase.key(word), word)
        }
        if (rules.isEmpty()) return text
        // One pass with the longest phrases first, so replacements never cascade.
        val alternatives = rules.keys.sortedByDescending { it.length }.mapNotNull { Phrase.pattern(it) }
        val regex = Regex(BOUNDARY_BEFORE + "(?:" + alternatives.joinToString("|") + ")" + BOUNDARY_AFTER, RegexOption.IGNORE_CASE)
        return regex.replace(text) { match ->
            val word = rules[Phrase.key(match.value)] ?: return@replace match.value
            matchSentenceCase(match.value, word)
        }
    }

    /**
     * The words to listen out for, in the form sherpa-onnx takes them: phrases separated by "/".
     * Empty if there are none.
     *
     * @param canSpell leaves out words the speech model can't write (see [BpeVocab.characters]),
     *   which it would otherwise only half listen for.
     */
    fun hotwords(entries: List<DictionaryEntry>, canSpell: (String) -> Boolean = { true }): String =
        entries.asSequence()
            .map { entry ->
                entry.word.replace("/", " ")
                    .split(Regex("\\s+"))
                    .map { it.trimStart(':') } // ":" starts a score in sherpa-onnx's format
                    .filter { it.isNotEmpty() }
                    .joinToString(" ")
            }
            .filter { it.isNotEmpty() && canSpell(it) }
            .distinct()
            .joinToString("/")

    /** "iPhone", "DBYoder", "NASA" and "Wispr Flow", but not "Will" or "will". */
    private fun fixesCapitals(word: String): Boolean =
        word.any { it.isWhitespace() } || word.drop(1).any { it.isUpperCase() }

    /**
     * "gonna" → "going to" at the start of a sentence becomes "Going to". Words with capitals of
     * their own ("iPhone") are always written exactly as given.
     */
    private fun matchSentenceCase(matched: String, word: String): String {
        val first = word.firstOrNull() ?: return word
        if (word.any { it.isUpperCase() }) return word
        return if (matched.firstOrNull()?.isUpperCase() == true && first.isLowerCase()) {
            first.uppercaseChar() + word.substring(1)
        } else {
            word
        }
    }
}

/** Matching spoken phrases loosely: any case, and spaces or hyphens between the words. */
internal object Phrase {
    private val SEPARATORS = Regex("[\\s\\-]+")

    /** "Wisper-Flow " → "wisper flow" */
    fun key(phrase: String): String =
        phrase.trim().lowercase().split(SEPARATORS).filter { it.isNotEmpty() }.joinToString(" ")

    /** A regex (no boundaries) for [phrase], or null if it has no words. */
    fun pattern(phrase: String): String? {
        val words = phrase.trim().split(SEPARATORS).filter { it.isNotEmpty() }
        if (words.isEmpty()) return null
        return words.joinToString("[\\s\\-]+") { Regex.escape(it) }
    }
}

/**
 * The speech model's word pieces with scores, which sherpa-onnx needs to listen out for
 * dictionary words. Newer model downloads include this file (bpe.vocab); for older ones it's
 * rebuilt from tokens.txt. The model's pieces come from SentencePiece BPE, which scores each
 * piece by the order it was learned in, and tokens.txt lists them in that order.
 */
object BpeVocab {
    /**
     * @param tokens the contents of tokens.txt ("piece id" per line).
     * @return the contents for bpe.vocab ("piece<TAB>score" per line), or null if [tokens] doesn't
     *   look like a word-piece list.
     */
    fun fromTokens(tokens: String): String? {
        val pieces = tokens.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val space = line.lastIndexOf(' ')
                if (space <= 0) return@mapNotNull null
                val id = line.substring(space + 1).toIntOrNull() ?: return@mapNotNull null
                line.substring(0, space) to id
            }
            .sortedBy { it.second }
            .toList()
        // SentencePiece marks the start of a word with "▁"; without it these aren't word pieces.
        if (pieces.none { it.first.startsWith("▁") }) return null
        val out = StringBuilder()
        var rank = 0
        for ((piece, _) in pieces) {
            if (piece.any { it.isWhitespace() }) continue
            val special = piece.startsWith("<") && piece.endsWith(">")
            if (piece == "<blk>" || piece == "<blank>") continue
            val score = if (special) 0 else -(rank++)
            out.append(piece).append('\t').append(score).append('\n')
        }
        return out.toString()
    }

    /**
     * Every character the speech model can write (its single-character pieces, as SentencePiece
     * keeps one for each). A word with any other character can't be spelled by it.
     */
    fun characters(tokens: String): Set<Char> =
        tokens.lineSequence()
            .mapNotNull { line -> line.trim().substringBeforeLast(' ', "").takeIf { it.isNotEmpty() } }
            .map { it.removePrefix("\u2581") }
            .filter { it.length == 1 }
            .map { it[0] }
            .toSet()
}
