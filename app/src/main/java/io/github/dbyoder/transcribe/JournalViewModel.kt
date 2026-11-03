package io.github.dbyoder.transcribe

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dbyoder.transcribe.ai.Formatter
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.audio.MAX_RECORDING_SECONDS
import io.github.dbyoder.transcribe.audio.MIN_RECORDING_SECONDS
import io.github.dbyoder.transcribe.core.EntryLabels
import io.github.dbyoder.transcribe.core.FormatStyle
import io.github.dbyoder.transcribe.core.JournalAsk
import io.github.dbyoder.transcribe.core.JournalDay
import io.github.dbyoder.transcribe.core.JournalEntry
import io.github.dbyoder.transcribe.core.JournalHit
import io.github.dbyoder.transcribe.core.JournalMarkdown
import io.github.dbyoder.transcribe.core.JournalSearch
import io.github.dbyoder.transcribe.core.TextJoin
import io.github.dbyoder.transcribe.history.Transcript
import io.github.dbyoder.transcribe.journal.Journal
import io.github.dbyoder.transcribe.model.ModelStore
import io.github.dbyoder.transcribe.speech.LiveTranscription
import io.github.dbyoder.transcribe.speech.TextPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** An answer from "Ask your journal", with the entries it was based on. */
data class JournalAnswer(val question: String, val text: String, val sources: List<JournalAsk.Source>)

/** A file ready to hand to the share sheet. */
data class ShareRequest(val uri: Uri, val title: String)

data class JournalUiState(
    val recorder: RecorderState = RecorderState.Idle,
    /** What the app is doing after a recording ("Polishing…", "Adding a title and tags…"). */
    val working: String? = null,
    val query: String = "",
    val results: List<JournalHit>? = null,
    val asking: Boolean = false,
    val answer: JournalAnswer? = null,
    /** Have the AI add a title and tags to each new entry. */
    val labels: Boolean = true,
    val share: ShareRequest? = null,
    val message: String? = null,
)

class JournalViewModel(app: Application) : AndroidViewModel(app) {
    val days: StateFlow<List<JournalDay>> = Journal.days
    val loading: StateFlow<Boolean> = Journal.loading
    val where: StateFlow<String?> = Journal.where

    private val recorder = AudioRecorder(MAX_RECORDING_SECONDS)
    private var live: LiveTranscription? = null

    private val _state = MutableStateFlow(JournalUiState(labels = AppSettings.journalLabels(app)))
    val state: StateFlow<JournalUiState> = _state.asStateFlow()

    private val context get() = getApplication<Application>()

    init {
        refresh()
    }

    /** Re-reads the journal's files (they may have been edited in another app). */
    fun refresh() {
        viewModelScope.launch { runCatching { Journal.reload() } }
    }

    // --- Where it's kept ------------------------------------------------------------------

    /** Keeps the journal in a folder picked with Android's folder picker. */
    fun useFolder(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { context.contentResolver.takePersistableUriPermission(uri, flags) }
        moveTo(uri.toString())
    }

    fun usePrivate() = moveTo(Journal.PRIVATE)

    private fun moveTo(setting: String) {
        viewModelScope.launch {
            try {
                val copied = Journal.moveTo(setting)
                val where = Journal.where.value ?: "the new place"
                show(if (copied > 0) "Your journal is now in $where ($copied days copied over)." else "Your journal is saved in $where.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Couldn't use that folder: ${e.message}")
            }
        }
    }

    // --- New entries ----------------------------------------------------------------------

    /** Called when the record button is tapped (after the microphone permission is granted). */
    fun toggleRecording() {
        when (_state.value.recorder) {
            RecorderState.Idle -> startRecording()
            is RecorderState.Recording -> viewModelScope.launch { finishRecording() }
            RecorderState.Transcribing -> Unit
        }
    }

    /** The app went off screen: Android stops giving hidden apps mic audio, so wrap up now. */
    fun onAppHidden() {
        if (_state.value.recorder is RecorderState.Recording) viewModelScope.launch { finishRecording() }
    }

    private fun startRecording() {
        if (!ModelStore(context).hasModel()) {
            show("Set up the speech model on the Dictate page first.")
            return
        }
        val transcription = LiveTranscription(viewModelScope)
        _state.update { it.copy(recorder = RecorderState.Recording(0f, 0f)) }
        try {
            recorder.start(
                scope = viewModelScope,
                onAudio = transcription::accept,
                onProgress = { seconds, level ->
                    _state.update { s ->
                        if (s.recorder is RecorderState.Recording) s.copy(recorder = RecorderState.Recording(seconds, level)) else s
                    }
                },
                onLimitReached = { viewModelScope.launch { finishRecording() } },
            )
            live = transcription
        } catch (e: Exception) {
            transcription.cancel()
            _state.update { it.copy(recorder = RecorderState.Idle) }
            show(e.message ?: "Couldn't start recording")
        }
    }

    private suspend fun finishRecording() {
        if (_state.value.recorder !is RecorderState.Recording) return
        val transcription = live ?: return
        live = null
        _state.update { it.copy(recorder = RecorderState.Transcribing) }
        try {
            val seconds = recorder.stop()
            if (seconds < MIN_RECORDING_SECONDS) {
                transcription.cancel()
                show("That was too short. Tap, speak, then tap again.")
                return
            }
            val result = transcription.finish()
            // The usual tidy-up, "scratch that", your words and snippets; but "send" means nothing here.
            val text = TextPipeline.process(context, result.text, allowSend = false).text
            if (text.isBlank()) {
                show("No speech detected")
                return
            }
            _state.update { it.copy(recorder = RecorderState.Idle) }
            save(polished(text), LocalDateTime.now())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            show("Couldn't save the entry: ${e.message}")
        } finally {
            _state.update { it.copy(recorder = RecorderState.Idle, working = null) }
        }
    }

    /** Auto-polish (if it's on), as for bubble dictations. Falls back to the words as said. */
    private suspend fun polished(text: String): String {
        if (!AppSettings.autoPolish(context) || !Formatter.isInstalled()) return text
        val words = text.split(Regex("\\s+")).count { it.isNotEmpty() }
        if (words !in 4..300) return text
        _state.update { it.copy(working = "Polishing…") }
        return try {
            TextJoin.matchEdges(Formatter.format(text, FormatStyle.POLISH), text).takeIf { it.length >= text.length / 2 } ?: text
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            text
        }
    }

    /** Saves a typed entry. */
    fun addTyped(title: String, body: String, tags: String) {
        if (body.isBlank()) return
        viewModelScope.launch {
            try {
                val entry = Journal.add(body)
                val date = LocalDate.now()
                val withLabels = entry.copy(title = title.trim().ifEmpty { null }, tags = parseTags(tags))
                if (withLabels != entry) Journal.replace(date, entry, withLabels)
                if (withLabels.title == null) label(date, withLabels)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Couldn't save the entry: ${e.message}")
            }
        }
    }

    /** "Save to journal" on a dictation: added at the time it was dictated. */
    fun addFromHistory(transcript: Transcript) {
        if (Journal.where.value == null) {
            show("Open the Journal page first to choose where to keep it.")
            return
        }
        viewModelScope.launch {
            try {
                val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(transcript.createdAt), ZoneId.systemDefault())
                save(transcript.text, at)
                show("Saved to your journal.")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Couldn't save to your journal: ${e.message}")
            }
        }
    }

    private suspend fun save(text: String, at: LocalDateTime) {
        _state.update { it.copy(working = "Saving…") }
        val entry = Journal.add(text, at)
        _state.update { it.copy(working = null) }
        label(at.toLocalDate(), entry)
    }

    /** Has the AI add a short title and tags, after the entry is safely saved. */
    private suspend fun label(date: LocalDate, entry: JournalEntry) {
        if (!_state.value.labels || !Formatter.isInstalled() || entry.body.length < 20) return
        _state.update { it.copy(working = "Adding a title and tags…") }
        try {
            val labels = EntryLabels.parse(Formatter.ask(EntryLabels.SYSTEM_INSTRUCTION, EntryLabels.prompt(entry.body)))
            if (labels.title != null || labels.tags.isNotEmpty()) {
                Journal.replace(date, entry, entry.copy(title = entry.title ?: labels.title, tags = (entry.tags + labels.tags).distinct()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The entry is saved; it just goes without a title.
        } finally {
            _state.update { it.copy(working = null) }
        }
    }

    fun setLabels(enabled: Boolean) {
        AppSettings.setJournalLabels(context, enabled)
        _state.update { it.copy(labels = enabled) }
    }

    // --- Editing ----------------------------------------------------------------------------

    fun updateEntry(date: LocalDate, old: JournalEntry, title: String, body: String, tags: String) {
        if (body.isBlank()) return deleteEntry(date, old)
        viewModelScope.launch {
            val new = old.copy(title = title.trim().ifEmpty { null }, body = body.trim(), tags = parseTags(tags))
            val ok = runCatching { Journal.replace(date, old, new) }.getOrDefault(false)
            if (!ok) show("That entry changed somewhere else, so it wasn't saved. Open it again to edit.")
            refreshResults()
        }
    }

    fun deleteEntry(date: LocalDate, entry: JournalEntry) {
        viewModelScope.launch {
            val ok = runCatching { Journal.delete(date, entry) }.getOrDefault(false)
            show(if (ok) "Entry deleted." else "Couldn't delete that entry: it changed somewhere else.")
            refreshResults()
        }
    }

    private fun parseTags(text: String): List<String> =
        text.split(',', ' ', '#').map { JournalMarkdown.tag(it) }.filter { it.isNotEmpty() }.distinct()

    // --- Finding things ---------------------------------------------------------------------

    fun search(query: String) {
        if (query.isBlank()) return
        _state.update { it.copy(query = query, results = JournalSearch.search(days.value, query), answer = null) }
    }

    private fun refreshResults() {
        val s = _state.value
        if (s.results != null) _state.update { it.copy(results = JournalSearch.search(days.value, s.query)) }
    }

    /** "Ask your journal": the on-device AI answers from the entries that match best. */
    fun ask(question: String) {
        if (question.isBlank() || _state.value.asking) return
        _state.update { it.copy(query = question, asking = true, answer = null, results = null) }
        viewModelScope.launch {
            try {
                val sources = JournalAsk.pick(question, days.value, LocalDate.now())
                val text = if (sources.isEmpty()) {
                    "There's nothing in your journal yet."
                } else {
                    Formatter.ask(JournalAsk.SYSTEM_INSTRUCTION, JournalAsk.prompt(question, sources))
                }
                _state.update { it.copy(answer = JournalAnswer(question, text, sources)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Couldn't answer that: ${e.message}")
            } finally {
                _state.update { it.copy(asking = false) }
            }
        }
    }

    fun clearResults() {
        _state.update { it.copy(results = null, answer = null) }
    }

    // --- Sharing ----------------------------------------------------------------------------

    fun shareDay(day: JournalDay) =
        share(listOf(day), "Journal: ${JournalMarkdown.titleOf(day.date)}", "journal-${day.date}.md")

    /** The last [days] days of entries, or everything if null. */
    fun shareRecent(days: Int?) {
        val all = this.days.value
        if (all.isEmpty()) return show("There's nothing in your journal to share yet.")
        val today = LocalDate.now()
        val picked = if (days == null) all else all.filter { it.date > today.minusDays(days.toLong()) }
        if (picked.isEmpty()) return show("No entries in the last $days days.")
        val from = picked.minOf { it.date }
        val to = picked.maxOf { it.date }
        val heading = "Journal: ${SHORT_DATE.format(from)} to ${SHORT_DATE.format(to)}"
        share(picked, heading, "journal-$from-to-$to.md")
    }

    private fun share(days: List<JournalDay>, heading: String, fileName: String) {
        viewModelScope.launch {
            try {
                val file = Journal.exportFile(days, heading, fileName)
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                _state.update { it.copy(share = ShareRequest(uri, heading)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                show("Couldn't share: ${e.message}")
            }
        }
    }

    fun shareHandled() {
        _state.update { it.copy(share = null) }
    }

    // --- Misc -------------------------------------------------------------------------------

    private fun show(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        recorder.cancel()
        live?.cancel()
    }

    companion object {
        private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)
    }
}
