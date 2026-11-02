package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PersonalDictionaryTest {
    private val words = listOf(
        DictionaryEntry("Wispr Flow", listOf("whisper flow", "wisper flow")),
        DictionaryEntry("Saoirse", listOf("seersha", "sir sha")),
        DictionaryEntry("iPhone"),
        DictionaryEntry("DBYoder", listOf("d b yoder")),
        DictionaryEntry("Will"),
        DictionaryEntry("going to", listOf("gonna")),
    )

    private fun fix(text: String) = PersonalDictionary.apply(text, words)

    @Test
    fun replacesWhatTheModelHeard() {
        assertEquals("I use Wispr Flow every day.", fix("I use whisper flow every day."))
        assertEquals("Ask Saoirse about it.", fix("Ask Seersha about it."))
        assertEquals("Ask Saoirse about it.", fix("Ask sir sha about it."))
        assertEquals("Message DBYoder now", fix("Message D B Yoder now"))
    }

    @Test
    fun ignoresCaseHyphensAndPossessives() {
        assertEquals("Wispr Flow's bubble", fix("Whisper-Flow's bubble"))
        assertEquals("Wispr Flow? Wispr Flow.", fix("WISPR FLOW? wisper   flow."))
    }

    @Test
    fun fixesUnusualCapitalsButLeavesEverydayWords() {
        assertEquals("My iPhone died", fix("My iphone died"))
        assertEquals("My iPhone died", fix("My IPhone died"))
        assertEquals("iPhone is fine.", fix("Iphone is fine."))
        // "Will" is a name but also an everyday word, so it's left as the model wrote it.
        assertEquals("I will ask Will", fix("I will ask Will"))
    }

    @Test
    fun onlyWholeWords() {
        assertEquals("whisper flowers", fix("whisper flowers"))
        assertEquals("seershake", fix("seershake"))
    }

    @Test
    fun keepsSentenceCaseForLowercaseWords() {
        assertEquals("Going to be late. I'm going to go.", fix("Gonna be late. I'm gonna go."))
    }

    @Test
    fun replacementsDontCascade() {
        val entries = listOf(DictionaryEntry("B", listOf("a")), DictionaryEntry("C", listOf("b")))
        assertEquals("B C", PersonalDictionary.apply("a b", entries))
    }

    @Test
    fun nothingToDo() {
        assertEquals("Hello there.", PersonalDictionary.apply("Hello there.", emptyList()))
        assertEquals("Hello there.", PersonalDictionary.apply("Hello there.", listOf(DictionaryEntry("  "))))
    }

    @Test
    fun hotwordsInSherpaOnnxFormat() {
        assertEquals("Wispr Flow/Saoirse/iPhone/DBYoder/Will/going to", PersonalDictionary.hotwords(words))
        assertEquals("AC DC/odd", PersonalDictionary.hotwords(listOf(DictionaryEntry("AC/DC"), DictionaryEntry(":odd"), DictionaryEntry(" "))))
        assertEquals("", PersonalDictionary.hotwords(emptyList()))
    }

    @Test
    fun bpeVocabFromTokens() {
        val tokens = "<unk> 0\n▁t 1\n▁th 2\ne 3\n<blk> 4\n"
        assertEquals("<unk>\t0\n▁t\t0\n▁th\t-1\ne\t-2\n", BpeVocab.fromTokens(tokens))
        // Order comes from the ids, not the line order.
        assertEquals(BpeVocab.fromTokens(tokens), BpeVocab.fromTokens("e 3\n<unk> 0\n▁th 2\n▁t 1\n"))
        assertNull(BpeVocab.fromTokens("a 0\nb 1\n"), "not SentencePiece pieces")
        assertNull(BpeVocab.fromTokens(""))
        assertTrue(BpeVocab.fromTokens("▁a 0")!!.isNotEmpty())
    }
}
