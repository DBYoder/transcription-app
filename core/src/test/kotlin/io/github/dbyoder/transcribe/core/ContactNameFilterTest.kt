package io.github.dbyoder.transcribe.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ContactNameFilterTest {
    @Test
    fun firstMiddleAndLastNamesOneWordEach() {
        assertEquals(
            listOf("Saoirse", "Ronan", "Mary-Jane", "O'Brien", "Nguyen"),
            ContactNameFilter.words(listOf("Saoirse", "Ronan", "Mary-Jane O’Brien", "Nguyen")),
        )
    }

    @Test
    fun leavesOutLabelsBusinessesAndEverydayWords() {
        assertEquals(
            listOf("Priya", "Joe's"),
            ContactNameFilter.words(listOf("Mom", "Dr. Priya", "Joe's Pizza", "Will", "Grace", "Home", "Dentist", "Jr")),
        )
    }

    @Test
    fun leavesOutNumbersSymbolsAndSingleLetters() {
        assertEquals(listOf("Ana"), ContactNameFilter.words(listOf("555-1234", "J", "Ana 2", "❤️", "@work")))
    }

    @Test
    fun tidiesCapitalsButKeepsDeliberateOnes() {
        assertEquals(
            listOf("John", "Smith", "McDonald", "DeShawn", "JD"),
            ContactNameFilter.words(listOf("john", "SMITH", "McDonald", "DeShawn", "JD")),
        )
    }

    @Test
    fun noDuplicatesAndACap() {
        assertEquals(listOf("Ana", "Ben"), ContactNameFilter.words(listOf("Ana", "ana", "ANA", "Ben", "Cal"), max = 2))
    }

    @Test
    fun hotwordsSkipWordsTheModelCantSpell() {
        val chars = BpeVocab.characters("<unk> 0\n▁ 1\n▁J 2\no 3\ns 4\nJ 5\n▁the 6\ne 7\n")
        assertEquals(setOf('J', 'o', 's', 'e'), chars)
        val spell = { w: String -> w.all { it == ' ' || it in chars } }
        assertEquals("Jose/Joe", PersonalDictionary.hotwords(listOf(DictionaryEntry("José"), DictionaryEntry("Jose"), DictionaryEntry("Joe")), spell))
    }
}
