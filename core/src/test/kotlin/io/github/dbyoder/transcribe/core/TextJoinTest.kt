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
    fun lowercasesEverydayFirstWordMidSentence() {
        assertEquals("maybe we should go.", join("Maybe we should go.", before = "I think "))
        assertEquals(" how are you?", join("How are you?", before = "Hello,"))
        assertEquals(" the plan", join("The plan.", before = "Here's", after = ", right?"))
    }

    @Test
    fun keepsNamesAcronymsAndIMidSentence() {
        assertEquals("John said hi.", join("John said hi.", before = "and "))
        assertEquals("NASA called.", join("NASA called.", before = "then "))
        assertEquals("I think so.", join("I think so.", before = "well "))
        assertEquals("I'm in.", join("I'm in.", before = "okay "))
        assertEquals("iPhone works.", join("iPhone works.", before = "my "))
    }

    @Test
    fun capitalizesAfterASentenceOrLineEnds() {
        assertEquals("We left.", join("we left.", before = "It rained. "))
        assertEquals("So we left.", join("So we left.", before = "It rained. "))
        assertEquals("Next.", join("next.", before = "Done!\n"))
    }

    @Test
    fun emptyBoxKeepsTheModelsCasing() {
        assertEquals("maybe later.", join("maybe later."))
        assertEquals("Maybe later.", join("Maybe later."))
    }

    @Test
    fun dropsClosingPeriodWhenTheSentenceContinues() {
        assertEquals("maybe tomorrow", join("Maybe tomorrow.", before = "See you ", after = " at noon"))
        assertEquals(" tomorrow", join("Tomorrow.", before = "See you", after = "."))
        assertEquals("tomorrow.", join("Tomorrow.", before = "See you "))
        assertEquals("Wait...", join("Wait...", after = " what"))
        assertEquals(" really? ", join("Really?", before = "Is it", after = "fine"))
    }

    @Test
    fun rewriteKeepsTheOriginalsEdges() {
        assertEquals("maybe we should leave early", TextJoin.matchEdges("Maybe we should leave early.", " maybe we uh should leave early "))
        assertEquals("We should leave early.", TextJoin.matchEdges("We should leave early.", "We should, um, leave early."))
        assertEquals("John will call", TextJoin.matchEdges("John will call.", "john will call"))
        assertEquals("One.\nTwo.", TextJoin.matchEdges("One.\nTwo.", "one two"))
    }

    @Test
    fun noTrailingSpaceBeforePunctuationOrSpace() {
        assertEquals(" fox", join("fox", before = "the", after = "."))
        assertEquals(" fox", join("fox", before = "the", after = " jumps"))
    }
}
