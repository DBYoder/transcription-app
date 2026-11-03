package io.github.dbyoder.transcribe.journal

import android.content.Context
import android.net.Uri
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.JournalDay
import io.github.dbyoder.transcribe.core.JournalEntry
import io.github.dbyoder.transcribe.core.JournalMarkdown
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The journal: one Markdown file per day in a folder you chose (or inside the app). Each change
 * re-reads that day's file first, so edits made in another app (Obsidian, say) aren't lost.
 */
object Journal {
    const val PRIVATE = "private"

    private lateinit var app: Context
    private val mutex = Mutex()
    private var folder: JournalFolder? = null

    private val _days = MutableStateFlow<List<JournalDay>>(emptyList())

    /** Every day with something in it, newest first. */
    val days: StateFlow<List<JournalDay>> = _days.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private val _where = MutableStateFlow<String?>(null)

    /** Where the files are ("Documents/Journal", "Inside this app"), or null before you choose. */
    val where: StateFlow<String?> = _where.asStateFlow()

    fun init(context: Context) {
        app = context.applicationContext
        folder = open(AppSettings.journalFolder(app))
        _where.value = folder?.description
    }

    private fun open(setting: String?): JournalFolder? = when (setting) {
        null -> null
        PRIVATE -> PrivateJournalFolder(app)
        else -> runCatching { DocumentJournalFolder(app, Uri.parse(setting)) }.getOrNull()
    }

    /** Reads every day's file again (picks up changes made in other apps). */
    suspend fun reload() = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = folder ?: return@withLock
            _loading.value = true
            try {
                _days.value = readAll(f).sortedByDescending { it.date }
            } finally {
                _loading.value = false
            }
        }
    }

    private fun readAll(f: JournalFolder): List<JournalDay> =
        f.list().mapNotNull { name ->
            val date = JournalMarkdown.dateOf(name) ?: return@mapNotNull null
            val text = runCatching { f.read(name) }.getOrNull() ?: return@mapNotNull null
            JournalMarkdown.parse(text, date).takeIf { it.entries.isNotEmpty() || it.intro.isNotBlank() }
        }

    /**
     * Keeps the journal somewhere new: [setting] is a folder's URI or [PRIVATE]. Days the new
     * place doesn't have yet are copied over from the old one (the old files are left alone).
     * Returns how many days were copied.
     */
    suspend fun moveTo(setting: String): Int = withContext(Dispatchers.IO) {
        mutex.withLock {
            val old = folder
            val new = open(setting) ?: throw IllegalStateException("Couldn't open that folder")
            var copied = 0
            if (old != null) {
                val existing = new.list().toSet()
                for (name in old.list()) {
                    if (JournalMarkdown.dateOf(name) == null || name in existing) continue
                    val text = old.read(name) ?: continue
                    new.write(name, text)
                    copied++
                }
            }
            AppSettings.setJournalFolder(app, setting)
            folder = new
            _where.value = new.description
            _days.value = readAll(new).sortedByDescending { it.date }
            copied
        }
    }

    /** Adds an entry at [at] (in time order within its day) and returns it as saved. */
    suspend fun add(body: String, at: LocalDateTime = LocalDateTime.now()): JournalEntry {
        val entry = JournalEntry(at.toLocalTime().withSecond(0).withNano(0), body.trim())
        change(at.toLocalDate()) { day ->
            val time = entry.time ?: LocalTime.MIDNIGHT
            val index = day.entries.indexOfLast { it.time != null && it.time!! <= time } + 1
            day.copy(entries = day.entries.toMutableList().apply { add(index.coerceIn(0, size), entry) })
        }
        return entry
    }

    /**
     * Swaps [old] for [new] on [date]. Returns false if [old] isn't there any more (changed in
     * the meantime, here or in another app).
     */
    suspend fun replace(date: LocalDate, old: JournalEntry, new: JournalEntry): Boolean {
        var found = false
        change(date) { day ->
            val i = day.entries.indexOf(old)
            found = i >= 0
            if (!found) day else day.copy(entries = day.entries.toMutableList().apply { set(i, new) })
        }
        return found
    }

    suspend fun delete(date: LocalDate, entry: JournalEntry): Boolean {
        var found = false
        change(date) { day ->
            found = entry in day.entries
            day.copy(entries = day.entries - entry)
        }
        return found
    }

    private suspend fun change(date: LocalDate, edit: (JournalDay) -> JournalDay) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val f = folder ?: throw IllegalStateException("Choose where to keep your journal first")
            val name = JournalMarkdown.fileName(date)
            val current = f.read(name)?.let { JournalMarkdown.parse(it, date) } ?: JournalDay(date, emptyList())
            val updated = edit(current)
            if (updated == current) return@withLock
            val empty = updated.entries.isEmpty() && updated.intro.isBlank()
            if (empty && updated.otherFrontMatter.isEmpty()) f.delete(name) else f.write(name, JournalMarkdown.serialize(updated))
            _days.value = (_days.value.filter { it.date != date } + listOfNotNull(updated.takeUnless { empty }))
                .sortedByDescending { it.date }
        }
    }
}
