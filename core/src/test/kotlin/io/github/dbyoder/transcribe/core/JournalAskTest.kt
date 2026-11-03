package io.github.dbyoder.transcribe.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JournalAskTest {
    private val today = LocalDate.of(2026, 11, 4)

    private fun day(date: LocalDate, vararg bodies: String) =
        JournalDay(date, bodies.mapIndexed { i, b -> JournalEntry(LocalTime.of(9 + i, 0), b) })

    private val days = listOf(
        day(LocalDate.of(2026, 11, 3), "Talked with Saoirse about the Q4 budget. We decided to wait on hiring."),
        day(LocalDate.of(2026, 10, 28), "Ran 5k in the park.", "Budget spreadsheet is a mess."),
        day(LocalDate.of(2026, 10, 2), "Dentist appointment."),
    )

    @Test
    fun picksMatchingEntriesOldestFirst() {
        val picked = JournalAsk.pick("What did I decide about the budget?", days, today)
        assertEquals(listOf(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 3)), picked.map { it.date })
    }

    @Test
    fun questionsAboutATimeGetThatTime() {
        val picked = JournalAsk.pick("What did I do last week?", days, today)
        assertEquals(listOf("Ran 5k in the park.", "Budget spreadsheet is a mess."), picked.map { it.entry.body })
    }

    @Test
    fun nothingMatchingMeansRecentEntries() {
        val picked = JournalAsk.pick("How have I been?", days, today, budgetChars = 200)
        assertEquals(LocalDate.of(2026, 11, 3), picked.last().date)
        assertTrue(picked.size < 4)
    }

    @Test
    fun staysWithinBudget() {
        val big = (1..20).map { day(today.minusDays(it.toLong()), "budget ".repeat(200)) }
        val picked = JournalAsk.pick("budget", big, today, budgetChars = 3000)
        assertTrue(picked.sumOf { it.entry.body.length } <= 3000)
        assertTrue(picked.isNotEmpty())
    }

    @Test
    fun promptHasDatesAndTheQuestion() {
        val sources = JournalAsk.pick("budget", days, today)
        val prompt = JournalAsk.prompt("What about the budget?", sources)
        assertTrue(prompt.contains("[Tue, Nov 3, 2026, 9:00 AM]\nTalked with Saoirse"), prompt)
        assertTrue(prompt.endsWith("Question: What about the budget?"), prompt)
    }

    @Test
    fun readsTheAIsLabels() {
        assertEquals(
            EntryLabels.Labels("Budget talk with Saoirse", listOf("work", "budget")),
            EntryLabels.parse("Title: \"Budget talk with Saoirse.\"\nTags: Work, #budget"),
        )
        assertEquals(
            EntryLabels.Labels("Morning run", listOf("running", "health", "park")),
            EntryLabels.parse("**Title:** Morning run\n**Tags:** running, health, park, outdoors"),
        )
        assertEquals(EntryLabels.Labels(null, emptyList()), EntryLabels.parse("Sure! Here you go."))
    }
}
