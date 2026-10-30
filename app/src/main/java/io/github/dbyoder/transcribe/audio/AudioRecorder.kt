package io.github.dbyoder.transcribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.dbyoder.transcribe.core.Pcm
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.core.SampleBuffer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Records 16 kHz mono audio from the microphone into memory.
 *
 * Call [start], then [stop] to get the samples. Not thread-safe: call both from the same
 * thread (the main thread in practice).
 */
class AudioRecorder(private val maxSeconds: Int) {
    private var record: AudioRecord? = null
    private var job: Job? = null
    private val buffer = SampleBuffer()

    val isRecording: Boolean get() = record != null

    /**
     * Starts recording. The caller must already hold the RECORD_AUDIO permission.
     *
     * @param onProgress called about every 100 ms (on a background thread) with the
     *   seconds recorded so far and the current loudness (0..1).
     * @param onLimitReached called (on a background thread) once [maxSeconds] is reached.
     *   Recording pauses there; the caller should then call [stop].
     * @throws IllegalStateException if the microphone can't be opened.
     */
    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        onProgress: (seconds: Float, level: Float) -> Unit,
        onLimitReached: () -> Unit,
    ) {
        check(record == null) { "Already recording" }
        val minBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        check(minBytes > 0) { "This phone's microphone can't record at 16 kHz" }

        val rec = AudioRecord(
            // Tuned for speech recognition: skips some of the processing meant for calls.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            max(minBytes, SAMPLE_RATE), // at least 0.5 s of 16-bit audio
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't open the microphone. Is another app using it?")
        }

        buffer.clear()
        rec.startRecording()
        record = rec
        job = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) break // the recorder was stopped or failed
                if (n == 0) continue
                buffer.append(chunk, n)
                onProgress(buffer.seconds, Pcm.level(chunk, n))
                if (buffer.seconds >= maxSeconds) {
                    onLimitReached()
                    break
                }
            }
        }
    }

    /** Stops recording and returns everything recorded since [start]. */
    suspend fun stop(): FloatArray {
        val rec = record ?: return FloatArray(0)
        val reader = job
        reader?.cancel()
        runCatching { rec.stop() } // unblocks a pending read()
        reader?.join()
        rec.release()
        record = null
        job = null
        val samples = buffer.toFloatArray()
        buffer.clear()
        return samples
    }

    /** Stops recording and throws the audio away. Safe to call from onCleared(). */
    fun cancel() {
        val rec = record ?: return
        job?.cancel()
        runCatching { rec.stop() }
        rec.release()
        record = null
        job = null
    }
}
