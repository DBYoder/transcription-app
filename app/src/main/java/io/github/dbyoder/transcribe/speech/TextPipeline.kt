package io.github.dbyoder.transcribe.speech

import android.content.Context
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.DictationPipeline
import io.github.dbyoder.transcribe.core.DictionaryEntry
import io.github.dbyoder.transcribe.core.ProcessedDictation
import io.github.dbyoder.transcribe.vocab.ContactNames
import io.github.dbyoder.transcribe.vocab.UserVocabulary

/** What happens to the speech model's text before it's typed or saved. */
object TextPipeline {
    /**
     * @param commands false for command-mode instructions: "send", "scratch that" and snippets
     *   are left as words for the AI to read.
     */
    fun process(context: Context, raw: String, commands: Boolean = true, allowSend: Boolean = true): ProcessedDictation =
        DictationPipeline.process(
            raw,
            cleanUp = AppSettings.cleanUp(context),
            // Contact names only change spelling for unusual capitals ("mcdonald" → "McDonald").
            words = UserVocabulary.words.value + ContactNames.cached(context).map { DictionaryEntry(it) },
            snippets = UserVocabulary.snippets.value,
            commands = commands,
            allowSend = allowSend,
        )
}
