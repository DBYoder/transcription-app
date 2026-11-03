package io.github.dbyoder.transcribe.core

/**
 * Spoken commands handled by the instant tidy-up:
 * - "scratch that" (or "strike that") deletes the sentence before it. Said first, it takes back
 *   the previous dictation instead.
 * - "<name> emoji" ("thumbs up emoji") types the emoji.
 * - Ending with "send", "send message", "press enter" or "hit enter" sends the message.
 */
object VoiceCommands {
    data class Scratched(val text: String, val removePrevious: Boolean)

    data class Sent(val text: String, val send: Boolean)

    // Its own phrase, with punctuation (a pause) or the start/end around it: not "scratch that itch".
    private val SCRATCH = Regex("""(?i)(?:^|(?<=[.,!?;:\n]))\s*(?:scratch|strike)\s+that(?=[.,!?;:]|\s*$)[.,!?;:]*""")
    private val SENTENCE_END = ".!?\n"

    fun scratch(text: String): Scratched {
        var out = text
        var removePrevious = false
        while (true) {
            val match = SCRATCH.find(out) ?: break
            val before = out.substring(0, match.range.first)
            val rest = out.substring(match.range.last + 1).trimStart(' ')
            if (before.isBlank()) {
                removePrevious = true
                out = rest
                continue
            }
            val kept = before.substring(0, sentenceStart(before.trimEnd(' '))).trimEnd(' ')
            out = when {
                kept.isEmpty() -> rest
                rest.isEmpty() || kept.endsWith('\n') -> kept + rest
                else -> "$kept $rest"
            }
        }
        return Scratched(out.trim(), removePrevious)
    }

    /** Where the last sentence of [text] starts (just after the sentence before it ends). */
    private fun sentenceStart(text: String): Int {
        // Ignore the sentence's own full stop: we want the end of the one before.
        val body = text.dropLastWhile { it in ".!?" }
        val end = body.indexOfLast { it in SENTENCE_END }
        return if (end < 0) 0 else end + 1
    }

    // --- Emoji ---------------------------------------------------------------------------

    private val EMOJI = mapOf(
        "thumbs up" to "👍", "thumbs down" to "👎", "smiley" to "😊", "smiley face" to "😊",
        "smile" to "😊", "smiling face" to "😊", "happy face" to "😊", "blush" to "😊",
        "grin" to "😁", "grinning" to "😀", "grinning face" to "😀", "laughing" to "😆",
        "crying laughing" to "😂", "laughing crying" to "😂", "tears of joy" to "😂", "lol" to "😂",
        "rolling on the floor laughing" to "🤣", "rofl" to "🤣", "wink" to "😉", "winking" to "😉",
        "winking face" to "😉", "heart" to "❤️", "red heart" to "❤️",
        "heart eyes" to "😍", "kiss" to "😘", "blowing a kiss" to "😘", "sad" to "😢", "sad face" to "😢",
        "crying" to "😭", "crying face" to "😭", "angry" to "😠", "angry face" to "😠", "mad" to "😠",
        "thinking" to "🤔", "thinking face" to "🤔", "fire" to "🔥", "party" to "🎉", "party popper" to "🎉",
        "tada" to "🎉", "celebrate" to "🎉", "clap" to "👏", "clapping" to "👏", "clapping hands" to "👏",
        "pray" to "🙏", "praying" to "🙏", "praying hands" to "🙏", "folded hands" to "🙏", "thank you" to "🙏",
        "hundred" to "💯", "one hundred" to "💯", "100" to "💯", "eyes" to "👀", "shrug" to "🤷",
        "shrugging" to "🤷", "facepalm" to "🤦", "face palm" to "🤦", "cool" to "😎", "sunglasses" to "😎",
        "surprised" to "😮", "shocked" to "😮", "check" to "✅", "check mark" to "✅", "checkmark" to "✅",
        "cross mark" to "❌", "x" to "❌", "star" to "⭐", "sparkles" to "✨", "rocket" to "🚀", "ok" to "👌",
        "okay" to "👌", "ok hand" to "👌", "wave" to "👋", "waving" to "👋", "waving hand" to "👋",
        "muscle" to "💪", "flexed biceps" to "💪", "strong" to "💪", "sweat smile" to "😅",
        "nervous laugh" to "😅", "upside down" to "🙃", "upside down face" to "🙃", "eye roll" to "🙄",
        "rolling eyes" to "🙄", "skull" to "💀", "dead" to "💀", "sun" to "☀️", "sunny" to "☀️",
        "coffee" to "☕", "beer" to "🍺", "beers" to "🍻", "cheers" to "🥂", "wine" to "🍷", "cake" to "🎂",
        "birthday cake" to "🎂", "gift" to "🎁", "present" to "🎁", "poop" to "💩", "dog" to "🐶",
        "cat" to "🐱", "money" to "💰", "grimace" to "😬", "grimacing" to "😬", "relieved" to "😌",
        "sleepy" to "😴", "sleeping" to "😴", "sick" to "🤒", "hug" to "🤗", "hugging" to "🤗",
        "hugging face" to "🤗", "mind blown" to "🤯", "exploding head" to "🤯", "raised hands" to "🙌",
        "raising hands" to "🙌", "point up" to "☝️", "pointing up" to "☝️", "point right" to "👉",
        "pointing right" to "👉", "fingers crossed" to "🤞", "crossed fingers" to "🤞", "salute" to "🫡",
        "melting" to "🫠", "melting face" to "🫠", "pleading" to "🥺", "pleading face" to "🥺",
        "puppy eyes" to "🥺", "neutral" to "😐", "neutral face" to "😐", "zipper mouth" to "🤐",
        "silly" to "😜", "tongue out" to "😛", "nerd" to "🤓", "money mouth" to "🤑", "wow" to "😮",
        "broken heart" to "💔", "purple heart" to "💜", "blue heart" to "💙", "green heart" to "💚",
        "yellow heart" to "💛", "black heart" to "🖤", "white heart" to "🤍", "pizza" to "🍕",
        "taco" to "🌮", "car" to "🚗", "airplane" to "✈️", "plane" to "✈️", "house" to "🏠",
        "phone" to "📱", "calendar" to "📅", "clock" to "🕐", "warning" to "⚠️", "light bulb" to "💡",
        "lightbulb" to "💡", "trophy" to "🏆", "medal" to "🏅", "flexing" to "💪", "handshake" to "🤝",
        "peace" to "✌️",
        "peace sign" to "✌️", "victory" to "✌️", "rainbow" to "🌈", "snowflake" to "❄️", "flower" to "🌸",
        "rose" to "🌹", "tree" to "🌳", "earth" to "🌍", "globe" to "🌍", "moon" to "🌙", "zzz" to "💤",
    )

    private val EMOJI_PATTERN = Regex(
        """(?i)(?<![\p{L}\p{N}])(""" +
            EMOJI.keys.sortedByDescending { it.length }.mapNotNull { Phrase.pattern(it) }.joinToString("|") +
            """)[\s\-]+emoji(?:s)?(?![\p{L}\p{N}])\.?""",
    )

    /** "Sounds good thumbs up emoji." → "Sounds good 👍" */
    fun emoji(text: String): String =
        EMOJI_PATTERN.replace(text) { match -> EMOJI[Phrase.key(match.groupValues[1])] ?: match.value }

    // --- Send ----------------------------------------------------------------------------

    /** "press enter" / "hit enter" at the very end. */
    private val ENTER = Regex("""(?i)([.!?]?)[\s,;:]*(?<![\p{L}\p{N}])(?:press|hit)\s+enter[.!?]*\s*$""")

    /** "send" or "send message" at the very end, and what came just before it. */
    private val SEND = Regex("""(?i)(?:(^|[.!?,;:])\s*|(?<=[\p{L}\p{N}'’])\s+)send(?:\s+(?:the\s+)?message)?[.!?]*\s*$""")

    /** "send it" at the very end, but only said as its own sentence ("Done. Send it."). */
    private val SEND_IT = Regex("""(?i)(^|[.!?])\s*send\s+it[.!?]*\s*$""")

    /**
     * Words that come before "send" when it's part of what you're saying rather than a command:
     * "I'll send", "can you send", "please send".
     */
    private val SEND_AS_A_WORD_AFTER = setOf(
        "i", "you", "we", "they", "he", "she", "it", "who", "i'll", "you'll", "we'll", "they'll", "he'll",
        "she'll", "ill", "i'd", "you'd", "we'd", "they'd", "will", "would", "could", "should", "can",
        "can't", "cannot", "won't", "wouldn't", "couldn't", "shouldn't", "to", "please", "pls", "plz",
        "just", "and", "or", "then", "also", "not", "don't", "didn't", "doesn't", "did", "do", "does",
        "let's", "lets", "gonna", "wanna", "gotta", "must", "might", "may", "never", "always", "still",
        "already", "i'm", "you're", "we're", "they're", "going", "want", "need", "forgot", "remember",
    )

    /**
     * Finds a "send" command at the end. Said after a pause (which the speech model marks with
     * punctuation) it always counts. Run straight on ("sounds good send") it counts unless the
     * word before makes "send" part of the sentence ("I'll send", "can you send").
     */
    fun trailingSend(text: String): Sent {
        val match = ENTER.find(text) ?: SEND_IT.find(text) ?: SEND.find(text)?.takeIf { isCommand(text, it) }
            ?: return Sent(text, false)
        val mark = match.groupValues[1].takeIf { it.isNotEmpty() && it in ".!?" }.orEmpty()
        val kept = text.substring(0, match.range.first).trimEnd(' ', ',', ';', ':') + mark
        return Sent(kept.trim(), true)
    }

    private fun isCommand(text: String, match: MatchResult): Boolean {
        if (match.groupValues[1].isNotEmpty()) return true // after punctuation, or the whole dictation
        val before = text.substring(0, match.range.first).trimEnd()
        if (before.isEmpty()) return true
        val previous = before.substringAfterLast(' ').lowercase().replace('’', '\'').trim { !it.isLetterOrDigit() && it != '\'' }
        return previous !in SEND_AS_A_WORD_AFTER
    }
}
