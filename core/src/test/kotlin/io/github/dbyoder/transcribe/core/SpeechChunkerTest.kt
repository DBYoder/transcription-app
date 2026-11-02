package io.github.dbyoder.transcribe.core

import java.util.Random
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeechChunkerTest {
    private val random = Random(42)

    /** Background hiss, about -59 dBFS. */
    private fun silence(seconds: Float) = FloatArray((seconds * SAMPLE_RATE).toInt()) {
        (random.nextFloat() * 2 - 1) * 0.002f
    }

    /** Speech-like sound: 180 ms "syllables" of tone with 70 ms gaps of hiss between them. */
    private fun speech(seconds: Float, amplitude: Float = 0.1f): FloatArray {
        val hiss = silence(seconds)
        val cycle = SAMPLE_RATE / 4 // 250 ms
        val voiced = SAMPLE_RATE * 18 / 100 // 180 ms
        return FloatArray(hiss.size) { i ->
            val inCycle = i % cycle
            if (inCycle < voiced) {
                // Vary the loudness a little from syllable to syllable, like a real voice.
                val loudness = amplitude * (0.6f + 0.4f * ((i / cycle) % 3) / 2f)
                loudness * sin(2 * PI * 180 * i / SAMPLE_RATE).toFloat() + hiss[i]
            } else {
                hiss[i]
            }
        }
    }

    private fun concat(vararg parts: FloatArray): FloatArray {
        val out = FloatArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            p.copyInto(out, at)
            at += p.size
        }
        return out
    }

    /** Feeds [audio] in 100 ms blocks, like the recorder does. Returns every piece, the rest last. */
    private fun chunk(audio: FloatArray, chunker: SpeechChunker = SpeechChunker()): List<FloatArray> {
        val pieces = mutableListOf<FloatArray>()
        val block = SAMPLE_RATE / 10
        var at = 0
        while (at < audio.size) {
            val end = minOf(at + block, audio.size)
            pieces += chunker.add(audio.copyOfRange(at, end))
            at = end
        }
        chunker.finish()?.let { pieces += it }
        // Nothing is lost or reordered.
        assertContentEquals(audio, concat(*pieces.toTypedArray()))
        return pieces
    }

    private fun FloatArray.seconds() = size / SAMPLE_RATE.toFloat()

    @Test
    fun shortRecordingStaysInOnePiece() {
        val audio = speech(8f)
        val pieces = chunk(audio)
        assertEquals(1, pieces.size)
    }

    @Test
    fun cutsInTheMiddleOfAPauseOnceLongEnough() {
        val pieces = chunk(concat(speech(12f), silence(1f), speech(5f)))
        assertEquals(2, pieces.size)
        val first = pieces[0].seconds()
        assertTrue(first in 12.1f..12.6f, "first piece was $first s")
    }

    @Test
    fun ignoresPausesBeforeTheMinimumLength() {
        val pieces = chunk(concat(speech(4f), silence(1f), speech(4f)))
        assertEquals(1, pieces.size)
    }

    @Test
    fun shortPausesOnlyCountOnceThePieceIsLong() {
        // A 0.4 s pause 12 s in is ignored...
        assertEquals(1, chunk(concat(speech(12f), silence(0.4f), speech(12f))).size)
        // ...but 22 s in, it's enough.
        val pieces = chunk(concat(speech(22f), silence(0.4f), speech(3f)))
        assertEquals(2, pieces.size)
        val first = pieces[0].seconds()
        assertTrue(first in 22.0f..22.4f, "first piece was $first s")
    }

    @Test
    fun nonStopSpeechIsCutAtTheQuietestMomentBeforeTheLimit() {
        // A 200 ms dip 27 s in (with the gap before it, still too short to count as a pause).
        val pieces = chunk(concat(speech(27f), silence(0.2f), speech(13f)))
        assertTrue(pieces.size >= 2)
        val first = pieces[0].seconds()
        assertTrue(first in 26.9f..27.2f, "first piece was $first s")
    }

    @Test
    fun noPieceIsLongerThanTheLimit() {
        val pieces = chunk(speech(95f))
        assertTrue(pieces.all { it.seconds() <= 30f }, pieces.map { it.seconds() }.toString())
        assertEquals(4, pieces.size)
    }

    @Test
    fun longDictationWithPausesSplitsAtEachPause() {
        val parts = mutableListOf<FloatArray>()
        repeat(12) {
            parts += speech(14f)
            parts += silence(1f)
        }
        val pieces = chunk(concat(*parts.toTypedArray()))
        // A cut in each pause, and the rest of the last pause left over.
        assertEquals(13, pieces.size)
        // Each cut lands inside a pause: every piece is about one sentence-run long.
        assertTrue(pieces.dropLast(1).all { it.seconds() in 14f..16f }, pieces.map { it.seconds() }.toString())
    }

    @Test
    fun quietSpeakerInANoisyRoom() {
        val audio = concat(speech(12f, amplitude = 0.05f), silence(1f), speech(5f, amplitude = 0.05f))
        // Room noise (about -45 dBFS) under everything: only about 12 dB below the voice.
        val withNoise = FloatArray(audio.size) { audio[it] + (random.nextFloat() * 2 - 1) * 0.01f }
        val pieces = chunk(withNoise)
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].seconds() in 12.0f..12.7f, "first piece was ${pieces[0].seconds()} s")
    }

    @Test
    fun silenceIsStillSplitAtTheLimit() {
        val pieces = chunk(silence(45f))
        assertEquals(2, pieces.size)
        assertTrue(pieces[0].seconds() <= 30f)
    }

    @Test
    fun finishStartsAfresh() {
        val chunker = SpeechChunker()
        chunk(concat(speech(12f), silence(1f), speech(5f)), chunker)
        assertEquals(null, chunker.finish())
        assertEquals(1, chunk(speech(5f), chunker).size)
    }

    @Test
    fun joinsPieceTranscripts() {
        assertEquals("Hello there. How are you?", SpeechChunker.join(listOf(" Hello there. ", "", "How are you?")))
        assertEquals("", SpeechChunker.join(emptyList()))
    }
}
