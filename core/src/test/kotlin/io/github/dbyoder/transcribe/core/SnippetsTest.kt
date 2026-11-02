package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class SnippetsTest {
    private val snippets = listOf(
        Snippet("my email", "david@example.com"),
        Snippet("signature", "Thanks,\nDavid"),
        Snippet("zoom link", "https://zoom.us/j/123"),
    )

    private fun expand(text: String) = Snippets.expand(text, snippets)

    @Test
    fun saidOnItsOwn() {
        assertEquals("david@example.com", expand("My email."))
        assertEquals("david@example.com", expand("my e-mail"))
        assertEquals("Thanks,\nDavid", expand("Signature."))
    }

    @Test
    fun insertAnywhere() {
        assertEquals("You can reach me at david@example.com", expand("You can reach me at insert my email."))
        assertEquals("See you then. Thanks,\nDavid", expand("See you then. Insert signature."))
        assertEquals("Join here: https://zoom.us/j/123 at noon", expand("Join here: insert zoom link at noon"))
    }

    @Test
    fun theTriggerWithoutInsertIsJustWords() {
        assertEquals("I'll check my email later.", expand("I'll check my email later."))
        assertEquals("Please send the zoom link.", expand("Please send the zoom link."))
    }

    @Test
    fun unusableSnippetsAreIgnored() {
        assertEquals("insert nothing", Snippets.expand("insert nothing", listOf(Snippet(" ", "x"), Snippet("nothing", ""))))
    }
}
