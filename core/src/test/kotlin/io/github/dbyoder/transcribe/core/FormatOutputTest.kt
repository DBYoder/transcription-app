package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FormatOutputTest {
    @Test
    fun plainReplyIsUnchanged() {
        assertEquals("Let's meet on Wednesday.", FormatOutput.clean("Let's meet on Wednesday."))
    }

    @Test
    fun stripsChattyPreamble() {
        assertEquals(
            "Let's meet on Wednesday.",
            FormatOutput.clean("Sure! Here's the cleaned-up text:\nLet's meet on Wednesday."),
        )
        assertEquals("Hi Sam,\n\nThanks.", FormatOutput.clean("Here is the email:\n\nHi Sam,\n\nThanks."))
    }

    @Test
    fun keepsTextThatMerelyStartsWithHere() {
        val text = "Here is where we landed on the budget."
        assertEquals(text, FormatOutput.clean(text))
    }

    @Test
    fun stripsCodeFenceAndWrappingQuotes() {
        assertEquals("Hello there.", FormatOutput.clean("```\nHello there.\n```"))
        assertEquals("Hello there.", FormatOutput.clean("\"Hello there.\""))
        assertEquals("Hello there.", FormatOutput.clean("\"\"\"\nHello there.\n\"\"\""))
        assertEquals("He said \"hi\" and left.", FormatOutput.clean("He said \"hi\" and left."))
    }

    @Test
    fun turnsMarkdownIntoPlainText() {
        val reply = "## Key points\n* **Budget** is approved\n- Launch moves to *May*\n  - Design review first"
        assertEquals(
            "Key points\n• Budget is approved\n• Launch moves to *May*\n  • Design review first",
            FormatOutput.clean(reply),
        )
    }

    @Test
    fun collapsesExtraBlankLines() {
        assertEquals("One.\n\nTwo.", FormatOutput.clean("One.\n\n\n\nTwo."))
    }

    @Test
    fun promptWrapsTheDictation() {
        val prompt = FormatStyle.prompt(FormatStyle.EMAIL, "  tell sam the launch moved  ")
        assertTrue(prompt.startsWith(FormatStyle.EMAIL.instruction))
        assertTrue(prompt.endsWith("\"\"\"\ntell sam the launch moved\n\"\"\""))
    }
}
