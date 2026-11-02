package io.github.dbyoder.transcribe.core

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Cuts a live recording into pieces at natural pauses, so each piece can be transcribed while
 * the speaker keeps talking. However long the dictation, only the last piece is left to do when
 * they stop.
 *
 * - A recording shorter than [minSeconds] stays in one piece, exactly as if it weren't split.
 * - After [minSeconds] of speech, a pause of [pauseSeconds] ends the piece; after
 *   [relaxAfterSeconds], a shorter pause of [shortPauseSeconds] will do. The cut goes in the
 *   middle of the pause, so both pieces keep a little silence around their words.
 * - No piece runs past [maxSeconds]: then the cut goes at the quietest moment of the last few
 *   seconds.
 *
 * "Quiet" is judged against the room and the voice: well above the background level (from the
 * quietest moments of the last few seconds) counts as speech, as does anything within 6 dB of
 * the recent loudest speech (which keeps a steady hum from passing as a pause).
 *
 * Not thread-safe: feed it from one thread (the recorder's).
 */
class SpeechChunker(
    sampleRate: Int = SAMPLE_RATE,
    minSeconds: Float = 10f,
    pauseSeconds: Float = 0.6f,
    relaxAfterSeconds: Float = 20f,
    shortPauseSeconds: Float = 0.3f,
    maxSeconds: Float = 30f,
) {
    private val frameSize = sampleRate / FRAMES_PER_SECOND
    private val minFrames = frames(minSeconds)
    private val pauseFrames = frames(pauseSeconds).coerceAtLeast(1)
    private val relaxFrames = frames(relaxAfterSeconds)
    private val shortPauseFrames = frames(shortPauseSeconds).coerceAtLeast(1)
    private val maxFrames = frames(maxSeconds)
    private val searchFrames = frames(SEARCH_SECONDS).coerceAtMost(maxFrames - SMOOTH_FRAMES - 1)

    init {
        require(maxFrames > 2 * SMOOTH_FRAMES) { "maxSeconds is too short" }
    }

    // The current piece: its audio, and the loudness of each whole 20 ms frame of it.
    private var samples = FloatArray(sampleRate * 2)
    private var sampleCount = 0
    private var levels = FloatArray(maxFrames + 1)
    private var frameCount = 0
    private var quietRun = 0

    // Recent loudness, across pieces, for judging what counts as quiet.
    private val recent = FloatArray(frames(FLOOR_WINDOW_SECONDS))
    private var recentCount = 0
    private var recentNext = 0
    private var framesSinceFloor = 0
    private var floor = 0f
    private var peak = 0f

    /** Adds recorded audio (16 kHz floats). Returns any pieces that are now complete, in order. */
    fun add(audio: FloatArray): List<FloatArray> {
        if (audio.isEmpty()) return emptyList()
        if (sampleCount + audio.size > samples.size) {
            samples = samples.copyOf(max(sampleCount + audio.size, samples.size * 2))
        }
        audio.copyInto(samples, sampleCount)
        sampleCount += audio.size

        val pieces = ArrayList<FloatArray>(0)
        while ((frameCount + 1) * frameSize <= sampleCount) {
            val level = rms(frameCount * frameSize)
            if (frameCount == levels.size) levels = levels.copyOf(levels.size * 2)
            levels[frameCount++] = level
            track(level)
            quietRun = if (level < quietThreshold()) quietRun + 1 else 0
            val cut = cutFrame()
            if (cut > 0) pieces += split(cut)
        }
        return pieces
    }

    /** Returns the rest of the recording (null if there's none) and starts afresh. */
    fun finish(): FloatArray? {
        val rest = if (sampleCount > 0) samples.copyOf(sampleCount) else null
        sampleCount = 0
        frameCount = 0
        quietRun = 0
        recentCount = 0
        recentNext = 0
        framesSinceFloor = 0
        floor = 0f
        peak = 0f
        return rest
    }

    /** Where to end the current piece (in frames), or 0 to keep going. */
    private fun cutFrame(): Int {
        val spoken = frameCount - quietRun // up to the start of the current pause
        return when {
            spoken >= minFrames && quietRun >= pauseFrames -> frameCount - quietRun / 2
            spoken >= relaxFrames && quietRun >= shortPauseFrames -> frameCount - quietRun / 2
            frameCount >= maxFrames -> quietestFrame()
            else -> 0
        }
    }

    /** The middle of the quietest tenth of a second in the last few seconds. */
    private fun quietestFrame(): Int {
        val half = SMOOTH_FRAMES / 2
        val last = frameCount - half - 1
        var best = last
        var bestSum = Float.MAX_VALUE
        for (center in (frameCount - searchFrames).coerceAtLeast(half + 1)..last) {
            var sum = 0f
            for (i in center - half..center + half) sum += levels[i]
            if (sum < bestSum) {
                bestSum = sum
                best = center
            }
        }
        return best
    }

    private fun split(cut: Int): FloatArray {
        val cutSample = cut * frameSize
        val piece = samples.copyOf(cutSample)
        samples.copyInto(samples, 0, cutSample, sampleCount)
        sampleCount -= cutSample
        levels.copyInto(levels, 0, cut, frameCount)
        frameCount -= cut
        quietRun = min(quietRun, frameCount)
        return piece
    }

    private fun track(level: Float) {
        peak = max(level, peak * PEAK_DECAY)
        recent[recentNext] = level
        recentNext = (recentNext + 1) % recent.size
        if (recentCount < recent.size) recentCount++
        framesSinceFloor++
        // Every frame at first (so the very start has a sensible floor), then twice a second.
        if (recentCount < FLOOR_EVERY || framesSinceFloor >= FLOOR_EVERY) {
            val sorted = recent.copyOf(recentCount).also { it.sort() }
            floor = sorted[recentCount / 10] // the 10th percentile: the gaps between words
            framesSinceFloor = 0
        }
    }

    private fun quietThreshold(): Float = max(MIN_QUIET, min(floor * FLOOR_RATIO, peak * PEAK_RATIO))

    private fun rms(start: Int): Float {
        var sum = 0.0
        for (i in start until start + frameSize) {
            val s = samples[i]
            sum += s * s
        }
        return sqrt(sum / frameSize).toFloat()
    }

    private fun frames(seconds: Float): Int = (seconds * FRAMES_PER_SECOND).toInt()

    companion object {
        private const val FRAMES_PER_SECOND = 50 // 20 ms frames
        private const val SEARCH_SECONDS = 5f
        private const val FLOOR_WINDOW_SECONDS = 10f
        private const val FLOOR_EVERY = 25
        private const val SMOOTH_FRAMES = 5

        /** Loudest-speech memory halves every 5 seconds. */
        private const val PEAK_DECAY = 0.99723f
        private const val FLOOR_RATIO = 3f // about 10 dB above the background
        private const val PEAK_RATIO = 0.5f // 6 dB below recent speech
        private const val MIN_QUIET = 0.0015f // about -56 dBFS

        /** Joins the pieces' transcripts back into one text. */
        fun join(texts: List<String>): String =
            texts.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(" ")
    }
}
