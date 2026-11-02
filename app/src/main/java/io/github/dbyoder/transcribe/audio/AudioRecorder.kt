package io.github.dbyoder.transcribe.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import io.github.dbyoder.transcribe.core.Pcm
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Longest single dictation. Long dictations are transcribed in pieces while you talk (see
 * LiveTranscription), so this is only a safety stop for a bubble left on by mistake.
 */
const val MAX_RECORDING_SECONDS = 30 * 60

/** Anything shorter is treated as an accidental tap. */
const val MIN_RECORDING_SECONDS = 0.3f

/**
 * Records 16 kHz mono audio from the microphone and hands it over as it arrives.
 *
 * Call [start], then [stop]. Not thread-safe: call both from the same thread (the main
 * thread in practice).
 */
class AudioRecorder(private val maxSeconds: Int) {
    private var record: AudioRecord? = null
    private var job: Job? = null

    @Volatile
    private var recordedSamples = 0L

    val isRecording: Boolean get() = record != null

    /**
     * Starts recording. The caller must already hold the RECORD_AUDIO permission.
     *
     * @param onAudio called about every 100 ms (on a background thread, never two at once) with
     *   the new audio as floats in -1..1.
     * @param onProgress called after each [onAudio] with the seconds recorded so far and the
     *   current loudness (0..1).
     * @param onLimitReached called (on a background thread) once [maxSeconds] is reached.
     *   Recording pauses there; the caller should then call [stop].
     * @throws IllegalStateException if the microphone can't be opened.
     */
    @SuppressLint("MissingPermission")
    fun start(
        scope: CoroutineScope,
        onAudio: (FloatArray) -> Unit,
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
            // Room for a couple of seconds, in case the phone is busy transcribing an earlier piece.
            max(minBytes, SAMPLE_RATE * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IllegalStateException("Couldn't open the microphone. Is another app using it?")
        }

        recordedSamples = 0
        rec.startRecording()
        record = rec
        job = scope.launch(Dispatchers.IO) {
            val chunk = ShortArray(SAMPLE_RATE / 10) // 100 ms
            while (isActive) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n < 0) break // the recorder was stopped or failed
                if (n == 0) continue
                onAudio(FloatArray(n) { Pcm.toFloat(chunk[it]) })
                recordedSamples += n
                val seconds = recordedSamples / SAMPLE_RATE.toFloat()
                onProgress(seconds, Pcm.level(chunk, n))
                if (seconds >= maxSeconds) {
                    onLimitReached()
                    break
                }
            }
        }
    }

    /**
     * Stops recording and returns how many seconds were recorded. Every bit of audio has been
     * passed to onAudio by the time this returns.
     */
    suspend fun stop(): Float {
        val rec = record ?: return 0f
        val reader = job
        reader?.cancel()
        runCatching { rec.stop() } // unblocks a pending read()
        reader?.join()
        rec.release()
        record = null
        job = null
        return recordedSamples / SAMPLE_RATE.toFloat()
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
