package io.github.dbyoder.transcribe.core

/** The one-tap AI formatting options, with the instructions given to the on-device model. */
enum class FormatStyle(val label: String, val instruction: String) {
    POLISH(
        "Polish",
        "Rewrite the dictated text so it reads cleanly. Fix grammar, punctuation and capitalization. " +
            "Remove filler words, false starts and repeated words. When the speaker corrects themselves " +
            "(for example \"Tuesday, no wait, Wednesday\"), keep only the correction. Split it into " +
            "paragraphs where the topic changes. Keep the speaker's own words, meaning and tone as much " +
            "as possible, and don't add anything new.",
    ),
    BULLETS(
        "Bullets",
        "Turn the dictated text into a short, well-organized bulleted list of its key points. One idea " +
            "per bullet, each starting with \"- \". Group related points together. Keep names, numbers " +
            "and dates exactly as given, and don't add anything new.",
    ),
    EMAIL(
        "Email",
        "Lay out the dictated text as an email body, written in the speaker's own voice. Keep their " +
            "words, phrasing and level of formality: only fix grammar and punctuation, remove filler words " +
            "and false starts, and organize it into short paragraphs, with lists, steps or action items as " +
            "bullets that start with \"- \". If they name the person they're writing to, start with " +
            "\"Hi <name>,\". End with a short sign-off such as \"Thanks,\" only if it fits what they said. " +
            "Don't make it more formal, and don't add phrases they didn't say (such as \"I hope this email " +
            "finds you well\" or \"Please let me know if you have any questions\"). Keep names, numbers and " +
            "dates exactly as given. No subject line or signature.",
    ),
    SHORTER(
        "Shorter",
        "Rewrite the dictated text to be about half as long while keeping every important point, name, " +
            "number and date. Keep the speaker's tone.",
    ),
    ;

    companion object {
        const val SYSTEM_INSTRUCTION =
            "You edit text that someone dictated by voice. It should still sound like them: keep their " +
                "own words, phrasing and tone unless asked otherwise. Reply with only the edited text: no " +
                "introduction, explanation, quotation marks or headings."

        /** The message sent to the model for [text]. */
        fun prompt(style: FormatStyle, text: String): String =
            "${style.instruction}\n\nDictated text:\n\"\"\"\n${text.trim()}\n\"\"\""
    }
}

/** Tidies the model's reply into plain text that looks right in any text box. */
object FormatOutput {
    private val PREAMBLE = Regex("""^(sure|okay|ok|certainly|of course)?[,!.]?\s*(here('s| is| are)\b[^\n]*:)\s*\n""", RegexOption.IGNORE_CASE)
    private val FENCE = Regex("""^```[a-zA-Z]*\n([\s\S]*?)\n```$""")
    private val BULLET = Regex("""^(\s*)[-*•]\s+""", RegexOption.MULTILINE)
    private val BOLD = Regex("""\*\*(.+?)\*\*|__(.+?)__""")
    private val HEADING = Regex("""^#{1,6}\s+""", RegexOption.MULTILINE)
    private val BLANK_LINES = Regex("""\n{3,}""")

    fun clean(reply: String): String {
        var s = reply.trim()
        s = PREAMBLE.replace(s, "").trim()
        FENCE.matchEntire(s)?.let { s = it.groupValues[1].trim() }
        s = stripWrappingQuotes(s)
        s = BOLD.replace(s) { it.groupValues[1].ifEmpty { it.groupValues[2] } }
        s = HEADING.replace(s, "")
        s = BULLET.replace(s) { "${it.groupValues[1]}• " } // plain-text bullets look right everywhere
        s = BLANK_LINES.replace(s, "\n\n")
        return s.trim()
    }

    private fun stripWrappingQuotes(s: String): String {
        for (quote in listOf("\"\"\"", "\"", "“")) {
            val close = if (quote == "“") "”" else quote
            if (s.length > quote.length + close.length && s.startsWith(quote) && s.endsWith(close)) {
                val inner = s.substring(quote.length, s.length - close.length)
                if (!inner.contains(quote)) return inner.trim()
            }
        }
        return s
    }
}
