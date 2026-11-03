package io.github.dbyoder.transcribe.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.temporal.TemporalAdjusters
import kotlin.math.ln

/** A journal entry that matched a search, with a bit of its text around the first match. */
data class JournalHit(
    val date: LocalDate,
    val entry: JournalEntry,
    val score: Double,
    val snippet: String,
)

/** Keyword search over the journal: instant, and good enough to find entries for the AI to read. */
object JournalSearch {
    private val NOT_WORDS = Regex("[^\\p{L}\\p{N}']+")

    private val STOP_WORDS = setOf(
        "a", "an", "the", "and", "or", "but", "if", "of", "to", "in", "on", "at", "by", "for", "with", "from",
        "about", "as", "into", "over", "after", "before", "is", "are", "was", "were", "be", "been", "am", "do",
        "does", "did", "have", "has", "had", "i", "me", "my", "mine", "we", "us", "our", "you", "your", "he",
        "she", "it", "they", "them", "their", "this", "that", "these", "those", "what", "when", "where", "who",
        "why", "how", "which", "any", "all", "some", "there", "here", "so", "than", "then", "too", "very",
        "can", "could", "would", "should", "will", "just", "not", "no", "yes", "up", "out", "say", "said",
        "tell", "told", "write", "wrote", "written", "mention", "mentioned", "journal", "entry", "entries",
        "today", "yesterday", "week", "month", "year", "last", "past", "recent", "recently", "ever", "time",
        "times", "anything", "something", "thing", "things", "i'm", "i've", "i'd", "i'll", "it's", "don't",
        "didn't", "get", "got", "go", "went", "think", "thought", "feel", "felt", "know", "knew",
        "january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
        "november", "december", "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
    )

    /** The words in a search or question worth looking for, reduced to their stems. */
    fun keywords(query: String): List<String> =
        query.lowercase().replace('’', '\'').split(NOT_WORDS)
            .map { it.trim('\'') }
            .filter { it.length >= 2 && it !in STOP_WORDS }
            .map(::stem)
            .filter { it.length >= 2 }
            .distinct()

    /**
     * "budgets" → "budget", "meeting" → "meet", "running" → "run": crude, but every form then
     * matches as a prefix.
     */
    internal fun stem(word: String): String {
        val cut = when {
            word.length > 5 && word.endsWith("ing") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ies") -> word.dropLast(3)
            word.length > 4 && word.endsWith("ed") -> word.dropLast(2)
            word.length > 4 && word.endsWith("es") && !word.endsWith("ses") -> word.dropLast(2)
            word.length > 3 && word.endsWith("s") && !word.endsWith("ss") -> return word.dropLast(1)
            else -> return word
        }
        // "runn" → "run", "stopp" → "stop", but "fall" and "miss" stay.
        return if (cut.length >= 3 && cut.last() == cut[cut.length - 2] && cut.last() !in "lsz") cut.dropLast(1) else cut
    }

    /**
     * Entries matching [query], best first: those matching more of its words, then rarer words
     * and matches in titles and tags, then newer entries.
     */
    fun search(days: List<JournalDay>, query: String, limit: Int = 30): List<JournalHit> {
        val words = keywords(query)
        if (words.isEmpty()) return emptyList()
        val patterns = words.associateWith { Regex("""(?<![\p{L}\p{N}])""" + Regex.escape(it), RegexOption.IGNORE_CASE) }
        val all = days.flatMap { day -> day.entries.map { day.date to it } }
        if (all.isEmpty()) return emptyList()
        // How many entries each word appears in: rare words count for more.
        val documentFrequency = words.associateWith { w ->
            all.count { (_, e) -> patterns.getValue(w).containsMatchIn(searchableText(e)) }
        }
        val hits = ArrayList<JournalHit>()
        for ((date, entry) in all) {
            val text = entry.body
            val labels = listOfNotNull(entry.title, entry.heading).joinToString(" ") + " " + entry.tags.joinToString(" ")
            var matched = 0
            var score = 0.0
            for (w in words) {
                val inText = patterns.getValue(w).findAll(text).count()
                val inLabels = patterns.getValue(w).findAll(labels).count()
                if (inText + inLabels == 0) continue
                matched++
                val idf = ln(1.0 + all.size.toDouble() / (1 + documentFrequency.getValue(w)))
                score += (1 + ln((inText + 3 * inLabels).toDouble())) * idf
            }
            if (matched == 0) continue
            hits += JournalHit(date, entry, matched * 100 + score, snippet(text, patterns.values))
        }
        return hits.sortedWith(compareByDescending<JournalHit> { it.score }.thenByDescending { it.date }.thenByDescending { it.entry.time })
            .take(limit)
    }

    private fun searchableText(e: JournalEntry): String =
        listOfNotNull(e.title, e.heading, e.body).joinToString(" ") + " " + e.tags.joinToString(" ")

    /** About [length] characters of [text] around the first match, cut at word breaks. */
    internal fun snippet(text: String, patterns: Collection<Regex>, length: Int = 160): String {
        val flat = text.replace(Regex("\\s+"), " ").trim()
        if (flat.length <= length) return flat
        val first = patterns.mapNotNull { it.find(flat)?.range?.first }.minOrNull() ?: 0
        var start = (first - length / 3).coerceAtLeast(0)
        var end = (start + length).coerceAtMost(flat.length)
        start = (end - length).coerceAtLeast(0)
        if (start > 0) start = flat.indexOf(' ', start).let { if (it < 0 || it >= first) start else it + 1 }
        if (end < flat.length) end = flat.lastIndexOf(' ', end).let { if (it <= first) end else it }
        return (if (start > 0) "…" else "") + flat.substring(start, end).trim() + (if (end < flat.length) "…" else "")
    }
}

/** Dates mentioned in a question about the journal: "last week", "yesterday", "in October". */
object JournalDates {
    private val PAST_DAYS = Regex("""(?i)\b(?:past|last)\s+(\d{1,3}|two|three|four|five|six|seven|ten|fourteen|thirty)\s+days\b""")
    private val NUMBERS = mapOf(
        "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7, "ten" to 10,
        "fourteen" to 14, "thirty" to 30,
    )
    private val WEEKDAY = Regex("""(?i)\b(?:on|last|this)\s+(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\b""")
    private val MONTH = Regex("""(?i)\b(january|february|march|april|may|june|july|august|september|october|november|december)\b""")

    /** The range of days a question is about, or null if it doesn't say. */
    fun rangeIn(question: String, today: LocalDate): ClosedRange<LocalDate>? {
        val q = question.lowercase()
        PAST_DAYS.find(q)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: NUMBERS.getValue(m.groupValues[1])
            return today.minusDays((n - 1).toLong().coerceAtLeast(0))..today
        }
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        when {
            Regex("""\byesterday\b""").containsMatchIn(q) -> return today.minusDays(1).let { it..it }
            Regex("""\btoday\b|\bthis morning\b|\btonight\b""").containsMatchIn(q) -> return today..today
            Regex("""\blast week\b""").containsMatchIn(q) -> return monday.minusWeeks(1)..monday.minusDays(1)
            Regex("""\bthis week\b""").containsMatchIn(q) -> return monday..today
            Regex("""\blast month\b""").containsMatchIn(q) -> {
                val first = today.withDayOfMonth(1).minusMonths(1)
                return first..first.with(TemporalAdjusters.lastDayOfMonth())
            }
            Regex("""\bthis month\b""").containsMatchIn(q) -> return today.withDayOfMonth(1)..today
            Regex("""\blast year\b""").containsMatchIn(q) -> {
                val first = today.withDayOfYear(1).minusYears(1)
                return first..first.with(TemporalAdjusters.lastDayOfYear())
            }
            Regex("""\bthis year\b""").containsMatchIn(q) -> return today.withDayOfYear(1)..today
        }
        WEEKDAY.find(q)?.let { m ->
            val day = DayOfWeek.valueOf(m.groupValues[1].uppercase())
            val date = if (m.value.startsWith("last") && today.dayOfWeek == day) {
                today.minusWeeks(1)
            } else {
                today.with(TemporalAdjusters.previousOrSame(day))
            }
            return date..date
        }
        MONTH.find(q)?.let { m ->
            // "may" is also a verb: only count it as a month after "in" or before a number.
            if (m.value == "may" && !Regex("""\bin may\b|\bmay \d""").containsMatchIn(q)) return null
            val month = Month.valueOf(m.groupValues[1].uppercase())
            var first = today.withMonth(month.value).withDayOfMonth(1)
            if (first.isAfter(today)) first = first.minusYears(1)
            return first..first.with(TemporalAdjusters.lastDayOfMonth())
        }
        return null
    }
}
