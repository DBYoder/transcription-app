package io.github.dbyoder.transcribe.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalMarkdownTest {
    private val date = LocalDate.of(2026, 11, 3)

    private val day = JournalDay(
        date,
        listOf(
            JournalEntry(LocalTime.of(9, 14), "Talked with Saoirse about the Q4 budget.\n\nWe agreed to wait.", "Budget talk", listOf("work")),
            JournalEntry(LocalTime.of(18, 40), "Dinner with the kids.", tags = listOf("family", "work")),
        ),
    )

    private val text = """
        ---
        date: 2026-11-03
        tags: [work, family]
        ---

        # Tuesday, November 3, 2026

        ## 9:14 AM · Budget talk
        #work

        Talked with Saoirse about the Q4 budget.

        We agreed to wait.

        ## 6:40 PM
        #family #work

        Dinner with the kids.
    """.trimIndent() + "\n"

    @Test
    fun writesReadableMarkdown() {
        assertEquals(text, JournalMarkdown.serialize(day))
    }

    @Test
    fun readsItBack() {
        assertEquals(day, JournalMarkdown.parse(text, date))
    }

    @Test
    fun keepsWhatOtherAppsAdd() {
        val edited = """
            ---
            date: 2026-11-03
            mood: good
            tags:
              - work
            aliases: [Budget day]
            ---
            # Tuesday, November 3, 2026
            A note I typed in Obsidian.

            ## 9:14 AM · Budget talk
            Some text.

            ## Things to do
            - [ ] Call Mum
        """.trimIndent()
        val parsed = JournalMarkdown.parse(edited, date)
        assertEquals(listOf("mood: good", "aliases: [Budget day]"), parsed.otherFrontMatter)
        assertEquals("A note I typed in Obsidian.", parsed.intro)
        assertEquals(2, parsed.entries.size)
        assertEquals(LocalTime.of(9, 14), parsed.entries[0].time)
        assertNull(parsed.entries[1].time)
        assertEquals("Things to do", parsed.entries[1].heading)
        assertEquals("- [ ] Call Mum", parsed.entries[1].body)
        // And writing it out again keeps all of it.
        val again = JournalMarkdown.parse(JournalMarkdown.serialize(parsed), date)
        assertEquals(parsed, again)
    }

    @Test
    fun readsTimesWrittenOtherWays() {
        fun timeOf(heading: String) = JournalMarkdown.parse("## $heading\nx", date).entries.single().time
        assertEquals(LocalTime.of(9, 14), timeOf("09:14"))
        assertEquals(LocalTime.of(21, 5), timeOf("9:05 p.m."))
        assertEquals(LocalTime.of(0, 30), timeOf("12:30 AM"))
        assertEquals(LocalTime.of(12, 30), timeOf("12:30 PM - Lunch"))
        assertNull(timeOf("25:00"))
    }

    @Test
    fun fileNames() {
        assertEquals("2026-11-03.md", JournalMarkdown.fileName(date))
        assertEquals(date, JournalMarkdown.dateOf("2026-11-03.md"))
        assertNull(JournalMarkdown.dateOf("notes.md"))
        assertNull(JournalMarkdown.dateOf("2026-13-45.md"))
    }

    @Test
    fun tags() {
        assertEquals("work-stuff", JournalMarkdown.tag("#Work stuff"))
        assertEquals("q4", JournalMarkdown.tag("Q4!"))
    }
}
