package io.github.dbyoder.transcribe.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalSearchTest {
    private val today = LocalDate.of(2026, 11, 4) // a Wednesday

    private fun day(date: LocalDate, vararg bodies: String) =
        JournalDay(date, bodies.mapIndexed { i, b -> JournalEntry(LocalTime.of(9 + i, 0), b) })

    private val days = listOf(
        day(LocalDate.of(2026, 11, 3), "Talked with Saoirse about the Q4 budget. We decided to wait on hiring."),
        day(LocalDate.of(2026, 10, 28), "Ran 5k in the park. Felt great.", "Budgeting spreadsheet is a mess."),
        day(LocalDate.of(2026, 10, 2), "Dentist appointment, then groceries."),
    )

    @Test
    fun keywordsDropFillerWordsAndEndings() {
        assertEquals(listOf("decide", "budget"), JournalSearch.keywords("What did I decide about the budgets?"))
        assertEquals(listOf("run", "park"), JournalSearch.keywords("When was I running in the park"))
    }

    @Test
    fun findsEntriesBestFirst() {
        val hits = JournalSearch.search(days, "budget decision")
        assertEquals(2, hits.size)
        assertEquals(LocalDate.of(2026, 11, 3), hits[0].date) // matches both words
        assertTrue(hits[1].entry.body.startsWith("Budgeting"))
    }

    @Test
    fun noWordsNoResults() {
        assertEquals(emptyList(), JournalSearch.search(days, "what did I"))
        assertEquals(emptyList(), JournalSearch.search(days, "zebra"))
    }

    @Test
    fun snippetsShowTheMatch() {
        val long = "Lorem ipsum dolor sit amet. ".repeat(10) + "The budget meeting went long. " + "More words here. ".repeat(10)
        val snippet = JournalSearch.snippet(long, listOf(Regex("budget", RegexOption.IGNORE_CASE)))
        assertTrue(snippet.contains("budget meeting"), snippet)
        assertTrue(snippet.startsWith("…") && snippet.endsWith("…"), snippet)
        assertTrue(snippet.length <= 170, snippet)
    }

    @Test
    fun datesInQuestions() {
        fun range(q: String) = JournalDates.rangeIn(q, today)
        assertEquals(today..today, range("What did I do today?"))
        assertEquals(today.minusDays(1).let { it..it }, range("how was yesterday"))
        assertEquals(LocalDate.of(2026, 10, 26)..LocalDate.of(2026, 11, 1), range("What happened last week?"))
        assertEquals(LocalDate.of(2026, 11, 2)..today, range("this week so far"))
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), range("anything about running last month"))
        assertEquals(LocalDate.of(2026, 10, 1)..LocalDate.of(2026, 10, 31), range("What did I do in October?"))
        assertEquals(LocalDate.of(2025, 12, 1)..LocalDate.of(2025, 12, 31), range("Christmas plans in December"))
        assertEquals(LocalDate.of(2026, 10, 29)..today, range("the past 7 days"))
        assertEquals(LocalDate.of(2026, 11, 2).let { it..it }, range("what did I do on Monday"))
        assertEquals(LocalDate.of(2026, 10, 28).let { it..it }, range("last Wednesday"))
        assertNull(range("Where may I have put the keys?"))
        assertNull(range("What did I decide about the budget?"))
    }
}
