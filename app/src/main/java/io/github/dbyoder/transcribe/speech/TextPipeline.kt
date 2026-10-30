package io.github.dbyoder.transcribe.speech

import android.content.Context
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.TextCleaner

/** What happens to the speech model's text before it's typed or saved. */
object TextPipeline {
    fun process(context: Context, raw: String): String =
        if (AppSettings.cleanUp(context)) TextCleaner.clean(raw) else raw.trim()
}
