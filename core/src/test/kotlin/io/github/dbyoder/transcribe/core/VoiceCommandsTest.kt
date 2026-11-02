package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VoiceCommandsTest {
    @Test
    fun scratchThatDeletesTheSentenceBeforeIt() {
        val r = VoiceCommands.scratch("Let's meet Tuesday. Scratch that. Let's meet Wednesday.")
        assertEquals("Let's meet Wednesday.", r.text)
        assertFalse(r.removePrevious)
        assertEquals("Hi John. Let's meet Wednesday.", VoiceCommands.scratch("Hi John. Let's meet Tuesday. Scratch that. Let's meet Wednesday.").text)
        assertEquals("Hi John.", VoiceCommands.scratch("Hi John. Let's meet Tuesday, scratch that.").text)
        assertEquals("Hi John,\nSee you.", VoiceCommands.scratch("Hi John,\nLet's meet. Strike that. See you.").text)
    }

    @Test
    fun scratchThatTwice() {
        assertEquals("C.", VoiceCommands.scratch("A. B. Scratch that. Scratch that. C.").text)
    }

    @Test
    fun scratchThatFirstTakesBackThePreviousDictation() {
        val r = VoiceCommands.scratch("Scratch that. Let's meet Wednesday.")
        assertTrue(r.removePrevious)
        assertEquals("Let's meet Wednesday.", r.text)
        val alone = VoiceCommands.scratch("Scratch that.")
        assertTrue(alone.removePrevious)
        assertEquals("", alone.text)
    }

    @Test
    fun scratchOnlyAsACommand() {
        assertEquals("Scratch the paint.", VoiceCommands.scratch("Scratch the paint.").text)
        assertEquals("I can't scratch that itch", VoiceCommands.scratch("I can't scratch that itch").text)
        assertEquals("Don't scratch that", VoiceCommands.scratch("Don't scratch that").text)
    }

    @Test
    fun emoji() {
        assertEquals("Sounds good 👍", VoiceCommands.emoji("Sounds good thumbs up emoji."))
        assertEquals("Happy birthday! 🎂 🎉", VoiceCommands.emoji("Happy birthday! Birthday cake emoji party emoji"))
        assertEquals("That's hilarious 😂", VoiceCommands.emoji("That's hilarious crying laughing emoji"))
        assertEquals("I love emoji.", VoiceCommands.emoji("I love emoji."))
        assertEquals("The emoji keyboard", VoiceCommands.emoji("The emoji keyboard"))
    }

    @Test
    fun sendAtTheEnd() {
        assertEquals(VoiceCommands.Sent("See you soon.", true), VoiceCommands.trailingSend("See you soon. Send."))
        assertEquals(VoiceCommands.Sent("See you soon", true), VoiceCommands.trailingSend("See you soon, send."))
        assertEquals(VoiceCommands.Sent("", true), VoiceCommands.trailingSend("Send."))
        assertEquals(VoiceCommands.Sent("On my way!", true), VoiceCommands.trailingSend("On my way! Send message."))
        assertEquals(VoiceCommands.Sent("Done", true), VoiceCommands.trailingSend("Done press enter"))
        assertEquals(VoiceCommands.Sent("Done.", true), VoiceCommands.trailingSend("Done. Hit enter."))
    }

    @Test
    fun sendAsAWordIsNotACommand() {
        assertFalse(VoiceCommands.trailingSend("I'll send.").send)
        assertFalse(VoiceCommands.trailingSend("Can you send?").send)
        assertFalse(VoiceCommands.trailingSend("Please review it, send it.").send)
        assertFalse(VoiceCommands.trailingSend("Send it to Bob tomorrow.").send)
    }
}
