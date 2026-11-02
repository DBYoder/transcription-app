package io.github.dbyoder.transcribe.speech

import android.os.SystemClock
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.core.SpeechChunker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.system.measureTimeMillis

/**
 * Transcribes a recording in pieces while it's still going. The audio is cut at natural pauses
 * ([SpeechChunker]) and each piece goes to the speech model as soon as it's cut, so however long
 * you talk, only the last piece (at most about 30 s of audio, around a second of work) is left
 * when you stop. A dictation under 10 seconds is transcribed in one go, as before.
 *
 * One per recording: feed it with [accept] while recording, then call [finish] or [cancel].
 */
class LiveTranscription(scope: CoroutineScope) {
    /**
     * @param computeMillis time the speech model spent on all the pieces.
     * @param waitMillis time from [finish] being called to the text being ready.
     */
    data class Result(val text: String, val audioSeconds: Float, val computeMillis: Long, val waitMillis: Long)

    private val chunker = SpeechChunker()
    private val queue = Channel<FloatArray>(Channel.UNLIMITED)
    private val pieces = mutableListOf<String>()
    private var computeMillis = 0L
    private var failure: Exception? = null
    private var sampleCount = 0L
    private var allZeros = true

    // Pieces are transcribed one at a time, in order.
    private val worker = scope.launch(Dispatchers.Default) {
        for (piece in queue) {
            if (failure != null) continue
            try {
                var text = ""
                computeMillis += measureTimeMillis { text = SpeechEngine.transcribe(piece) }
                pieces += text
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                failure = e
            }
        }
    }

    /**
     * True if every sample so far was exactly zero: what Android feeds an app it won't let use
     * the microphone.
     */
    val heardNothing: Boolean get() = allZeros

    /** Takes recorded audio. Call from one thread at a time (the recorder's). */
    fun accept(audio: FloatArray) {
        sampleCount += audio.size
        if (allZeros && audio.any { it != 0f }) allZeros = false
        for (piece in chunker.add(audio)) queue.trySend(piece)
    }

    /** Transcribes whatever is left and returns the whole text. Call once recording has stopped. */
    suspend fun finish(): Result {
        val stoppedAt = SystemClock.elapsedRealtime()
        chunker.finish()?.let { queue.trySend(it) }
        queue.close()
        worker.join()
        failure?.let { throw it }
        return Result(
            text = SpeechChunker.join(pieces),
            audioSeconds = sampleCount / SAMPLE_RATE.toFloat(),
            computeMillis = computeMillis,
            waitMillis = SystemClock.elapsedRealtime() - stoppedAt,
        )
    }

    /** Throws the recording away. */
    fun cancel() {
        queue.close()
        worker.cancel()
    }
}
