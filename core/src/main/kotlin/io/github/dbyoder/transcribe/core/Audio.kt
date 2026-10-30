package io.github.dbyoder.transcribe.core

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Speech models expect 16 kHz mono audio. */
const val SAMPLE_RATE = 16_000

object Pcm {
    private const val SHORT_SCALE = 32768f

    /** Converts one 16-bit PCM sample to the -1..1 float range the model expects. */
    fun toFloat(sample: Short): Float = sample / SHORT_SCALE

    /**
     * Loudness of a chunk of audio as 0..1, for a level meter.
     * Maps -60 dBFS (near silence) to 0 and 0 dBFS (full scale) to 1.
     */
    fun level(samples: ShortArray, count: Int = samples.size): Float {
        if (count <= 0) return 0f
        var sumSquares = 0.0
        for (i in 0 until count) {
            val s = samples[i] / 32768.0
            sumSquares += s * s
        }
        val rms = sqrt(sumSquares / count)
        val db = 20 * log10(rms.coerceAtLeast(1e-6))
        return ((db + 60) / 60).coerceIn(0.0, 1.0).toFloat()
    }
}

/** A growable buffer of float audio samples. Not thread-safe. */
class SampleBuffer(initialCapacity: Int = SAMPLE_RATE * 10) {
    private var data = FloatArray(initialCapacity.coerceAtLeast(1))

    var size: Int = 0
        private set

    val seconds: Float get() = size / SAMPLE_RATE.toFloat()

    fun append(samples: ShortArray, count: Int = samples.size) {
        require(count in 0..samples.size) { "count $count out of range" }
        ensureCapacity(size + count)
        for (i in 0 until count) data[size + i] = Pcm.toFloat(samples[i])
        size += count
    }

    fun toFloatArray(): FloatArray = data.copyOf(size)

    fun clear() {
        size = 0
    }

    private fun ensureCapacity(needed: Int) {
        if (needed > data.size) data = data.copyOf(max(needed, data.size * 2))
    }
}
