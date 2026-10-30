package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

/** Spoken punctuation, run through the full tidy-up the way dictation is. */
class SpokenPunctuationTest {
    private fun clean(text: String) = TextCleaner.clean(text)

    @Test
    fun commaAndQuestionMark() {
        assertEquals("Hi John, how are you?", clean("Hi John comma how are you question mark"))
    }

    @Test
    fun replacesThePunctuationTheModelGuessed() {
        assertEquals("Hi John, how are you?", clean("Hi John, comma, how are you? Question mark."))
        assertEquals("Hi John, how are you?", clean("Hi John. Comma. How are you? Question mark."))
    }

    @Test
    fun periodAndFullStopEndTheSentence() {
        assertEquals("That's all.", clean("That's all period"))
        assertEquals("I'm done. See you tomorrow", clean("I'm done period see you tomorrow"))
        assertEquals("I'm done. See you tomorrow", clean("I'm done full stop see you tomorrow"))
    }

    @Test
    fun exclamationPointOrMark() {
        assertEquals("Great news! We won", clean("Great news exclamation point we won"))
        assertEquals("Great news!", clean("Great news exclamation mark."))
    }

    @Test
    fun colonSemicolonAndEllipsis() {
        assertEquals("Three things: milk, eggs and bread.", clean("Three things colon milk, eggs and bread."))
        assertEquals("It rained; we stayed in", clean("It rained semicolon we stayed in"))
        assertEquals("It rained; we stayed in", clean("It rained semi colon We stayed in"))
        assertEquals("Well... maybe", clean("Well ellipsis maybe"))
    }

    @Test
    fun quotesAndParentheses() {
        assertEquals("She said \"hello\" to me", clean("She said open quote hello close quote to me"))
        assertEquals("She said \"hello\" to me", clean("She said begin quote hello end quote to me"))
        assertEquals("Bring snacks (and drinks) please", clean("Bring snacks open paren and drinks close paren please"))
    }

    @Test
    fun periodAndColonStayWordsWhenUsedAsNouns() {
        assertEquals("The trial period ends Friday.", clean("The trial period ends Friday."))
        assertEquals("Over a period of time.", clean("Over a period of time."))
        assertEquals("That was a tough period for us.", clean("That was a tough period for us."))
        assertEquals("He had surgery on his colon.", clean("He had surgery on his colon."))
    }

    @Test
    fun markSaidOnItsOwnIsKept() {
        assertEquals("?", clean("Question mark."))
        assertEquals(",", clean("Comma"))
        assertEquals(".", clean("Period."))
    }

    @Test
    fun worksAlongsideFillersAndLineBreaks() {
        assertEquals("So, what do you think?", clean("Um, so comma what do you think question mark"))
        assertEquals("Hi John,\nHow are you?", clean("Hi John comma, new line, how are you question mark"))
    }

    @Test
    fun textWithoutSpokenMarksIsUntouched() {
        val text = "Let's meet on Tuesday at 3 p.m. to review the budget, then grab dinner."
        assertEquals(text, clean(text))
    }
}
