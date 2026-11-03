package io.github.dbyoder.transcribe.core

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * One journal entry: a heading with the time (and an optional title), an optional line of
 * #tags, and the text.
 *
 * @param time when it was written; null for a section someone added by hand in another app,
 *   whose heading is then kept as [heading].
 */
data class JournalEntry(
    val time: LocalTime?,
    val body: String,
    val title: String? = null,
    val tags: List<String> = emptyList(),
    val heading: String? = null,
) {
    /** "9:14 AM · Budget talk", or a hand-written heading. */
    val label: String
        get() {
            val t = time ?: return heading ?: ""
            return JournalMarkdown.timeLabel(t) + (title?.let { " · $it" } ?: "")
        }
}

/**
 * A day of the journal: one Markdown file, `2026-11-03.md`.
 *
 * @param otherFrontMatter front-matter lines other than date and tags, kept as they were (so
 *   properties added in Obsidian survive).
 * @param intro anything between the day's title and its first entry, kept as it was.
 */
data class JournalDay(
    val date: LocalDate,
    val entries: List<JournalEntry>,
    val otherFrontMatter: List<String> = emptyList(),
    val intro: String = "",
) {
    val fileName: String get() = JournalMarkdown.fileName(date)

    /** Every entry's tags, in order of first use. */
    val tags: List<String> get() = entries.flatMap { it.tags }.distinct()
}

/**
 * The journal's file format: plain Markdown with a little front matter, so Obsidian, other
 * note apps and AI assistants like Claude can all read it.
 *
 * ```
 * ---
 * date: 2026-11-03
 * tags: [work, family]
 * ---
 *
 * # Monday, November 3, 2026
 *
 * ## 9:14 AM · Budget talk
 * #work
 *
 * Talked with Saoirse about the Q4 budget…
 * ```
 *
 * Reading is forgiving: files edited by hand (or in another app) keep whatever they add.
 */
object JournalMarkdown {
    private val TITLE_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US)
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
    private val FILE_NAME = Regex("""^(\d{4}-\d{2}-\d{2})\.md$""")
    private val TIME_HEADING = Regex("""^(\d{1,2}):(\d{2})(?:\s*([AaPp])\.?\s*[Mm]\.?)?(?:\s*[·•|–—-]\s*(.*))?$""")
    private val TAG = Regex("""#[\p{L}\p{N}_/-]+""")

    fun fileName(date: LocalDate): String = "$date.md"

    /** The date a journal file is for, or null if it isn't a journal file ("2026-11-03.md"). */
    fun dateOf(fileName: String): LocalDate? {
        val match = FILE_NAME.matchEntire(fileName) ?: return null
        return runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull()
    }

    fun timeLabel(time: LocalTime): String = time.format(TIME_FORMAT)

    fun titleOf(date: LocalDate): String = date.format(TITLE_FORMAT)

    /** "#Work stuff" → "work-stuff" */
    fun tag(raw: String): String =
        raw.trim().trimStart('#').lowercase().replace(Regex("\\s+"), "-")
            .filter { it.isLetterOrDigit() || it in "-_/" }
            .trim('-', '_', '/')

    fun parse(text: String, date: LocalDate): JournalDay {
        val lines = text.replace("\r\n", "\n").split("\n")
        var i = 0
        val otherFrontMatter = ArrayList<String>()
        if (lines.firstOrNull()?.trim() == "---") {
            val end = (1 until lines.size).firstOrNull { lines[it].trim() == "---" }
            if (end != null) {
                var inTagList = false
                for (line in lines.subList(1, end)) {
                    val key = line.substringBefore(':', "").trim().lowercase()
                    when {
                        inTagList && line.trimStart().startsWith("-") -> Unit // a "tags:" list item
                        key == "date" || key == "tags" -> inTagList = key == "tags"
                        else -> {
                            inTagList = false
                            otherFrontMatter += line
                        }
                    }
                }
                i = end + 1
            }
        }

        // The day's "# Title" line, if any, is rebuilt from the date.
        val intro = StringBuilder()
        var titleSeen = false
        while (i < lines.size && !lines[i].startsWith("## ")) {
            val line = lines[i]
            if (!titleSeen && line.startsWith("# ")) {
                titleSeen = true
            } else {
                intro.append(line).append('\n')
            }
            i++
        }

        val entries = ArrayList<JournalEntry>()
        while (i < lines.size) {
            val heading = lines[i].removePrefix("## ").trim()
            i++
            val body = ArrayList<String>()
            while (i < lines.size && !lines[i].startsWith("## ")) {
                body += lines[i]
                i++
            }
            entries += entry(heading, body)
        }
        return JournalDay(date, entries, otherFrontMatter, intro.toString().trim('\n'))
    }

    private fun entry(heading: String, bodyLines: List<String>): JournalEntry {
        var lines = bodyLines.dropWhile { it.isBlank() }
        var tags = emptyList<String>()
        val first = lines.firstOrNull()?.trim().orEmpty()
        if (first.isNotEmpty() && first.split(Regex("\\s+")).all { TAG.matches(it) }) {
            tags = first.split(Regex("\\s+")).map { tag(it) }.filter { it.isNotEmpty() }.distinct()
            lines = lines.drop(1)
        }
        val body = lines.joinToString("\n").trim('\n', ' ')
        val match = TIME_HEADING.matchEntire(heading)
        val time = match?.let { toTime(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3]) }
        return if (match != null && time != null) {
            JournalEntry(time, body, title = match.groupValues[4].trim().ifEmpty { null }, tags = tags)
        } else {
            JournalEntry(null, body, tags = tags, heading = heading)
        }
    }

    private fun toTime(hour: Int, minute: Int, amPm: String): LocalTime? {
        if (minute !in 0..59) return null
        val h = when (amPm.lowercase()) {
            "a" -> if (hour == 12) 0 else hour
            "p" -> if (hour == 12) 12 else hour + 12
            else -> hour
        }
        return if (h in 0..23) LocalTime.of(h, minute) else null
    }

    fun serialize(day: JournalDay): String {
        val out = StringBuilder()
        out.append("---\n")
        out.append("date: ").append(day.date).append('\n')
        val tags = day.tags
        if (tags.isNotEmpty()) out.append("tags: [").append(tags.joinToString(", ")).append("]\n")
        for (line in day.otherFrontMatter) out.append(line).append('\n')
        out.append("---\n\n")
        out.append(body(day))
        return out.toString()
    }

    /** The day without its front matter: its title, any intro, and its entries. */
    private fun body(day: JournalDay): String {
        val out = StringBuilder()
        out.append("# ").append(titleOf(day.date)).append("\n\n")
        if (day.intro.isNotBlank()) out.append(day.intro.trim('\n')).append("\n\n")
        for (entry in day.entries) {
            out.append("## ").append(entry.label).append('\n')
            if (entry.tags.isNotEmpty()) out.append(entry.tags.joinToString(" ") { "#$it" }).append('\n')
            if (entry.body.isNotBlank()) out.append('\n').append(entry.body.trim('\n')).append('\n')
            out.append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }

    /**
     * Several days as one Markdown document (oldest first), for sharing with an AI assistant:
     * a heading, then each day with its entries.
     */
    fun combined(days: List<JournalDay>, heading: String): String {
        val out = StringBuilder("# ").append(heading).append("\n\n")
        for (day in days.sortedBy { it.date }) {
            out.append(body(day).replaceFirst("# ", "## ").replace("\n## ", "\n### ")).append('\n')
        }
        return out.toString().trimEnd('\n') + "\n"
    }
}
