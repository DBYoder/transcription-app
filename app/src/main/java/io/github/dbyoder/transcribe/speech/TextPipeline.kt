package io.github.dbyoder.transcribe.speech

import android.content.Context
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.DictationPipeline
import io.github.dbyoder.transcribe.core.ProcessedDictation
import io.github.dbyoder.transcribe.vocab.UserVocabulary

/** What happens to the speech model's text before it's typed or saved. */
object TextPipeline {
    /**
     * @param commands false for command-mode instructions: "send", "scratch that" and snippets
     *   are left as words for the AI to read.
     */
    fun process(context: Context, raw: String, commands: Boolean = true): ProcessedDictation =
        DictationPipeline.process(
            raw,
            cleanUp = AppSettings.cleanUp(context),
            words = UserVocabulary.words.value,
            snippets = UserVocabulary.snippets.value,
            commands = commands,
        )
}
