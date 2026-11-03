package io.github.dbyoder.transcribe.core

/**
 * A dictation ready to type.
 *
 * @param removePrevious the dictation started with "scratch that": take back the previous one first.
 * @param send it ended with "send" or "press enter": send the message after typing it.
 */
data class ProcessedDictation(
    val text: String,
    val removePrevious: Boolean = false,
    val send: Boolean = false,
)

/** Everything that happens to the speech model's text before it's typed, in order. */
object DictationPipeline {
    /**
     * @param cleanUp the instant tidy-up (fillers, spoken punctuation and the spoken commands).
     * @param commands whether "scratch that", emoji and "send" count. Off for command-mode
     *   instructions, which are read by the AI rather than typed.
     * @param allowSend whether a final "send" counts (not in the journal, where there's nothing to send).
     */
    fun process(
        raw: String,
        cleanUp: Boolean,
        words: List<DictionaryEntry> = emptyList(),
        snippets: List<Snippet> = emptyList(),
        commands: Boolean = true,
        allowSend: Boolean = true,
    ): ProcessedDictation {
        var text = if (cleanUp) TextCleaner.clean(raw) else raw.trim()
        var removePrevious = false
        var send = false
        if (cleanUp && commands) {
            val scratched = VoiceCommands.scratch(text)
            text = scratched.text
            removePrevious = scratched.removePrevious
            // Before emoji: "thumbs up emoji. Send." needs its full stop to count as a pause.
            if (allowSend) {
                val sent = VoiceCommands.trailingSend(text)
                text = sent.text
                send = sent.send
            }
            text = VoiceCommands.emoji(text)
        }
        // Your own words and snippets apply even with the tidy-up off: you set them up yourself.
        text = PersonalDictionary.apply(text, words)
        if (commands) text = Snippets.expand(text, snippets.map { it.copy(trigger = PersonalDictionary.apply(it.trigger, words)) })
        return ProcessedDictation(text.trim(), removePrevious, send)
    }
}
