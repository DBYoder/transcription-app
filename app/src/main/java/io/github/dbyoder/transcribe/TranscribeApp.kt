package io.github.dbyoder.transcribe

import android.app.Application
import io.github.dbyoder.transcribe.ai.Formatter
import io.github.dbyoder.transcribe.history.TranscriptHistory
import io.github.dbyoder.transcribe.speech.SpeechEngine

class TranscribeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpeechEngine.init(this)
        Formatter.init(this)
        TranscriptHistory.init(this)
    }
}
