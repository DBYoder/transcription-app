package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AudioTest {
    @Test
    fun levelIsZeroForSilenceAndNearOneForFullScale() {
        assertEquals(0f, Pcm.level(ShortArray(1600)))
        assertEquals(0f, Pcm.level(ShortArray(0)))
        val loud = ShortArray(1600) { if (it % 2 == 0) Short.MAX_VALUE else Short.MIN_VALUE }
        assertTrue(Pcm.level(loud) > 0.99f)
    }

    @Test
    fun levelGrowsWithVolume() {
        fun tone(amplitude: Int) = ShortArray(1600) { i ->
            (amplitude * kotlin.math.sin(i * 2 * Math.PI * 440 / SAMPLE_RATE)).toInt().toShort()
        }
        val quiet = Pcm.level(tone(300))
        val loud = Pcm.level(tone(10_000))
        assertTrue(quiet in 0f..1f && loud in 0f..1f)
        assertTrue(loud > quiet, "loud=$loud quiet=$quiet")
    }

    @Test
    fun levelOnlyLooksAtCountSamples() {
        val samples = ShortArray(100)
        samples[99] = Short.MAX_VALUE
        assertEquals(0f, Pcm.level(samples, 50))
    }
}
