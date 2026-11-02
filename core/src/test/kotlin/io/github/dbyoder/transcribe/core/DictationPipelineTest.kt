package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class DictationPipelineTest {
    private val words = listOf(DictionaryEntry("Saoirse", listOf("seersha")))
    private val snippets = listOf(Snippet("my email", "david@example.com"))

    private fun run(raw: String, cleanUp: Boolean = true, commands: Boolean = true) =
        DictationPipeline.process(raw, cleanUp, words, snippets, commands)

    @Test
    fun everythingTogether() {
        assertEquals(
            ProcessedDictation("Hi Saoirse, my address is david@example.com 👍", send = true),
            run("Um, hi seersha comma my address is insert my email thumbs up emoji. Send."),
        )
    }

    @Test
    fun scratchThatAtTheStart() {
        assertEquals(ProcessedDictation("Let's do Wednesday.", removePrevious = true), run("Scratch that. Let's do Wednesday."))
    }

    @Test
    fun tidyUpOffKeepsCommandsAsWordsButStillUsesYourWords() {
        assertEquals(ProcessedDictation("Um, hi Saoirse. Send."), run("Um, hi seersha. Send.", cleanUp = false))
    }

    @Test
    fun commandModeInstructionsSkipCommandsAndSnippets() {
        assertEquals(ProcessedDictation("Send this to Saoirse, insert my email."), run("Send this to seersha, insert my email.", commands = false))
    }
}
