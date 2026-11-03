package io.github.dbyoder.transcribe.core

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Ask your journal": picks the entries that answer a question (by date and keywords, within
 * what the on-device AI can read at once) and builds the message for it.
 */
object JournalAsk {
    /** About 1,500 words: room for the question and a full answer in the model's context. */
    const val DEFAULT_BUDGET_CHARS = 7000

    const val SYSTEM_INSTRUCTION =
        "You help someone look back through their own journal. Answer their question using only the " +
            "journal entries you're given, in a few sentences, speaking to them as \"you\". Say which dates " +
            "you're drawing on, like (Nov 3). If the entries don't answer the question, say you couldn't " +
            "find it in these entries. Never make anything up."

    private val DATE_LABEL = DateTimeFormatter.ofPattern("EEE, MMM d, yyyy", Locale.US)

    data class Source(val date: LocalDate, val entry: JournalEntry)

    /**
     * The entries to show the AI, oldest first. A question about a time ("last week") gets that
     * time's entries, best matches first; otherwise the best keyword matches; failing that, the
     * most recent entries.
     */
    fun pick(
        question: String,
        days: List<JournalDay>,
        today: LocalDate,
        budgetChars: Int = DEFAULT_BUDGET_CHARS,
    ): List<Source> {
        val range = JournalDates.rangeIn(question, today)
        val inRange = if (range == null) days else days.filter { it.date in range }
        val hits = JournalSearch.search(inRange, question, limit = 50).map { Source(it.date, it.entry) }
        val ordered = when {
            range != null -> (hits + newestFirst(inRange)).distinct()
            hits.isNotEmpty() -> hits
            else -> newestFirst(days)
        }
        val picked = ArrayList<Source>()
        var used = 0
        for (source in ordered) {
            val size = source.entry.body.length + 60
            if (picked.isNotEmpty() && used + size > budgetChars) continue
            picked += source
            used += size
            if (used >= budgetChars) break
        }
        return picked.sortedWith(compareBy<Source> { it.date }.thenBy { it.entry.time })
    }

    private fun newestFirst(days: List<JournalDay>): List<Source> =
        days.sortedByDescending { it.date }.flatMap { day -> day.entries.reversed().map { Source(day.date, it) } }

    /** The message for the AI: the entries, then the question. Very long entries are cut short. */
    fun prompt(question: String, sources: List<Source>, budgetChars: Int = DEFAULT_BUDGET_CHARS): String {
        val perEntry = (budgetChars / sources.size.coerceAtLeast(1)).coerceAtLeast(600)
        val out = StringBuilder("Journal entries:\n\n")
        for (s in sources) {
            out.append('[').append(label(s)).append("]\n")
            val body = s.entry.body.trim()
            out.append(if (body.length > perEntry) body.take(perEntry).trimEnd() + "…" else body).append("\n\n")
        }
        out.append("Question: ").append(question.trim())
        return out.toString()
    }

    /** "Mon, Nov 3, 2026, 9:14 AM · Budget talk" */
    fun label(source: Source): String {
        val time = source.entry.label.takeIf { it.isNotEmpty() }?.let { ", $it" }.orEmpty()
        return source.date.format(DATE_LABEL) + time
    }
}

/** A short title and a few topic tags for a journal entry, suggested by the on-device AI. */
object EntryLabels {
    private const val MAX_BODY_CHARS = 4000

    const val SYSTEM_INSTRUCTION =
        "You label journal entries so they're easy to find later. Reply with exactly two lines and " +
            "nothing else."

    data class Labels(val title: String?, val tags: List<String>)

    fun prompt(body: String): String {
        val text = body.trim().let { if (it.length > MAX_BODY_CHARS) it.take(MAX_BODY_CHARS) + "…" else it }
        return "Journal entry:\n\"\"\"\n$text\n\"\"\"\n\n" +
            "Reply with exactly these two lines:\n" +
            "Title: <a short title for the entry, at most 6 words>\n" +
            "Tags: <1 to 3 one-word topics, lowercase, separated by commas>"
    }

    fun parse(reply: String): Labels {
        var title: String? = null
        var tags = emptyList<String>()
        for (line in reply.lines()) {
            val clean = line.trim().trim('*', '-', '•', ' ')
            val key = clean.substringBefore(':', "").trim().lowercase()
            val value = clean.substringAfter(':', "").trim().trim('*', ' ')
            when (key) {
                "title" -> title = value.trim('"', '“', '”', '\'', '*', ' ').trimEnd('.').take(60).ifEmpty { null }
                "tags", "tag", "topics" -> tags = value.split(',', ';', '#')
                    .map { JournalMarkdown.tag(it) }
                    .filter { it.length in 2..30 }
                    .distinct()
                    .take(3)
            }
        }
        return Labels(title, tags)
    }
}
