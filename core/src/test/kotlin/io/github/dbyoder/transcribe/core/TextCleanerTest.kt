package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TextCleanerTest {
    private fun clean(text: String) = TextCleaner.clean(text)

    @Test
    fun removesFillerAtStartAndCapitalizes() {
        assertEquals("So I think we should go.", clean("Um, so I think we should go."))
    }

    @Test
    fun removesCommaWrappedFiller() {
        assertEquals("I think we should go.", clean("I think, uh, we should go."))
        assertEquals("I need three things.", clean("I need, um, three things."))
    }

    @Test
    fun keepsCommaAfterOpeners() {
        assertEquals("So, I was thinking.", clean("So, um, I was thinking."))
        assertEquals("Well, it's fine.", clean("Well, uh, it's fine."))
    }

    @Test
    fun movesSentenceEndingOffARemovedFiller() {
        assertEquals("That's all.", clean("That's all, um."))
        assertEquals("Is that right?", clean("Is that right, hmm?"))
    }

    @Test
    fun capitalizesNewSentenceAfterRemovedFiller() {
        assertEquals("Okay. So then we left.", clean("Okay. Um, so then we left."))
    }

    @Test
    fun collapsesStutters() {
        assertEquals("The meeting is at three.", clean("The the meeting is at three."))
        assertEquals("I think so.", clean("I, I think so."))
        assertEquals("I think so.", clean("I I I think so."))
        assertEquals("We should go to the store.", clean("We should go to to the store."))
    }

    @Test
    fun keepsMeaningfulRepeats() {
        assertEquals("What it is is a test.", clean("What it is is a test."))
        assertEquals("I know that that is true.", clean("I know that that is true."))
        assertEquals("No no no.", clean("No no no."))
        assertEquals("Really, really good.", clean("Really, really good."))
    }

    @Test
    fun removesCommaWrappedPhrases() {
        assertEquals("It was great.", clean("It was, you know, great."))
        assertEquals("It was huge.", clean("It was, like, huge."))
        assertEquals("Well, it's fine.", clean("Well, you know, it's fine."))
        assertEquals("It was, um, great.".let(::clean), "It was great.")
    }

    @Test
    fun leavesNormalUsesAlone() {
        assertEquals("I like it.", clean("I like it."))
        assertEquals("You know what I mean?", clean("You know what I mean?"))
        assertEquals("Uh-huh, sounds good.", clean("Uh-huh, sounds good."))
        assertEquals("Meet me at 7 a.m. tomorrow.", clean("Meet me at 7 a.m. tomorrow."))
        assertEquals("Use 5 mm screws.", clean("Use 5 mm screws."))
        assertEquals("To err is human.", clean("To err is human."))
    }

    @Test
    fun removesLongerFillerSpellings() {
        assertEquals("That's odd.", clean("Hmmm. That's odd."))
        assertEquals("I see.", clean("Ahh, I see."))
        assertEquals("Let me check.", clean("Ummm, let me check."))
    }

    @Test
    fun spokenParagraphAndLineBreaks() {
        assertEquals("Hi there.\n\nHow are you?", clean("Hi there. New paragraph. How are you?"))
        assertEquals("Dear John,\n\nThanks for coming.", clean("Dear John, new paragraph, thanks for coming."))
        assertEquals("Milk,\nEggs.", clean("Milk, new line, eggs."))
        assertEquals("Done.\n", clean("Done. New line."))
    }

    @Test
    fun newLineInsideASentenceIsNotACommand() {
        assertEquals("We need a new line of credit.", clean("We need a new line of credit."))
        assertEquals("New line of credit approved.", clean("New line of credit approved."))
    }

    @Test
    fun onlyFillersBecomesEmpty() {
        assertEquals("", clean("Um."))
        assertEquals("", clean("Uh, um."))
        assertEquals("", clean("   "))
    }

    @Test
    fun cleanTextIsUnchanged() {
        val text = "Let's meet on Tuesday at 3 p.m. to review the budget, then grab dinner."
        assertEquals(text, clean(text))
    }
}
