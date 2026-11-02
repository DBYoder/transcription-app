package io.github.dbyoder.transcribe.core

/**
 * Saved text you can insert by voice.
 *
 * @param trigger what you say ("my email", "signature").
 * @param text what goes in ("david@example.com", a multi-line sign-off...).
 */
data class Snippet(val trigger: String, val text: String)

/**
 * Expands snippets in dictated text. Say "insert" and the trigger anywhere ("thanks, insert
 * signature"), or say just the trigger on its own as the whole dictation ("my email").
 */
object Snippets {
    fun expand(text: String, snippets: List<Snippet>): String {
        val usable = snippets.filter { Phrase.key(it.trigger).isNotEmpty() && it.text.isNotEmpty() }
        if (usable.isEmpty() || text.isBlank()) return text

        val whole = loose(text)
        usable.firstOrNull { loose(it.trigger) == whole }?.let { return it.text }

        val byKey = usable.associateBy { Phrase.key(it.trigger) }
        val alternatives = byKey.keys.sortedByDescending { it.length }.mapNotNull { Phrase.pattern(it) }
        // "insert my email" plus any full stop or comma the speech model put after it.
        val regex = Regex(
            """(?<![\p{L}\p{N}])insert\s+(""" + alternatives.joinToString("|") + """)(?![\p{L}\p{N}])[.,]?""",
            RegexOption.IGNORE_CASE,
        )
        return regex.replace(text) { match ->
            byKey[Phrase.key(match.groupValues[1])]?.text ?: match.value
        }
    }

    /** Letters and digits only: "My e-mail." → "myemail" */
    private fun loose(s: String): String = s.lowercase().filter { it.isLetterOrDigit() }
}
