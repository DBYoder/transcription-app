package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextJoinTest {
    private fun join(text: String, before: String = "", after: String = "") = TextJoin.forInsertion(text, before, after)

    @Test
    fun emptyBoxGetsTextAsIs() {
        assertEquals("Hello there.", join("Hello there."))
    }

    @Test
    fun trimsWhitespaceFromTheModel() {
        assertEquals("Hello.", join("  Hello.  "))
        assertEquals("", join("   "))
    }

    @Test
    fun addsSpaceAfterAWordOrSentence() {
        assertEquals(" world.", join("world.", before = "Hello"))
        assertEquals(" See you soon.", join("See you soon.", before = "Thanks."))
        assertEquals(" ok", join("ok", before = "(really)"))
    }

    @Test
    fun noExtraSpaceWhenOneIsAlreadyThere() {
        assertEquals("world.", join("world.", before = "Hello "))
        assertEquals("Next line.", join("Next line.", before = "First line.\n"))
    }

    @Test
    fun noSpaceAfterOpeningBracketOrQuote() {
        assertEquals("aside", join("aside", before = "an ("))
        assertEquals("quoted", join("quoted", before = "she said \""))
    }

    @Test
    fun punctuationAttachesToPreviousWord() {
        assertEquals(", and then", join(", and then", before = "first"))
        assertEquals(".", join(".", before = "end"))
    }

    @Test
    fun addsSpaceBeforeFollowingWord() {
        assertEquals("quick ", join("quick", before = "The ", after = "fox"))
        assertEquals(" quick ", join("quick", before = "The", after = "fox"))
    }

    @Test
    fun noTrailingSpaceBeforePunctuationOrSpace() {
        assertEquals(" fox", join("fox", before = "the", after = "."))
        assertEquals(" fox", join("fox", before = "the", after = " jumps"))
    }
}
