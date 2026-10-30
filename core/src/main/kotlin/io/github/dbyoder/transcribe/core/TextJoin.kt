package io.github.dbyoder.transcribe.core

/** Fits dictated text into whatever is already in a text box. */
object TextJoin {
    // Characters that attach to the word before them, so no space goes in front.
    private const val CLOSING = ".,!?;:)]}%"

    /**
     * Returns [text] adjusted for insertion between [before] (the text left of the cursor)
     * and [after] (the text right of it): trimmed, with a space added where words would
     * otherwise run together.
     */
    fun forInsertion(text: String, before: String, after: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return ""

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
}
