package io.github.dbyoder.transcribe.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dbyoder.transcribe.JournalAnswer
import io.github.dbyoder.transcribe.JournalUiState
import io.github.dbyoder.transcribe.R
import io.github.dbyoder.transcribe.RecorderState
import io.github.dbyoder.transcribe.core.JournalAsk
import io.github.dbyoder.transcribe.core.JournalDay
import io.github.dbyoder.transcribe.core.JournalEntry
import io.github.dbyoder.transcribe.core.JournalHit
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** What the Journal page can ask the rest of the app to do. */
internal class JournalActions(
    val onChooseFolder: () -> Unit,
    val onKeepPrivate: () -> Unit,
    val onRecord: () -> Unit,
    val onType: () -> Unit,
    val onSearch: (String) -> Unit,
    val onAsk: (String) -> Unit,
    val onClear: () -> Unit,
    val onOpen: (LocalDate, JournalEntry) -> Unit,
    val onShareDay: (JournalDay) -> Unit,
    val onShareRecent: (Int?) -> Unit,
    val onLabels: (Boolean) -> Unit,
)

@Composable
internal fun JournalPage(
    padding: PaddingValues,
    where: String?,
    loading: Boolean,
    days: List<JournalDay>,
    state: JournalUiState,
    speechReady: Boolean,
    aiReady: Boolean,
    actions: JournalActions,
) {
    Page(padding) {
        item {
            Header(
                "Journal",
                "Say what's on your mind. Entries are saved as Markdown files, one per day, that you can " +
                    "search here or share with Claude.",
            )
        }
        if (where == null) {
            item { JournalSetupCard(actions) }
        } else {
            item { NewEntryCard(state, speechReady, actions) }
            item { FindCard(state, aiReady, actions) }
            item { ShareCard(actions) }
            if (days.isEmpty()) {
                item {
                    if (loading) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        Hint("No entries yet. Record your first one above.")
                    }
                }
            }
            items(days, key = { it.date.toString() }) { day -> DayCard(day, actions) }
            item { JournalSettingsCard(where, aiReady, state.labels, actions) }
        }
    }
}

@Composable
private fun JournalSetupCard(actions: JournalActions) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Where should your journal live?", style = MaterialTheme.typography.titleMedium)
            Text(
                "In a folder you choose, other apps can use the files too: open them in Obsidian, or sync the " +
                    "folder to your computer or Google Drive so Claude can read your whole journal.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = actions.onChooseFolder, modifier = Modifier.fillMaxWidth()) { Text("Choose a folder") }
            Hint("Tip: make a new folder called Journal in Documents, then tap \"Use this folder\".")
            OutlinedButton(onClick = actions.onKeepPrivate, modifier = Modifier.fillMaxWidth()) {
                Text("Keep it inside this app")
            }
            Hint("Private to this app. You can still share entries, and move to a folder later.")
        }
    }
}

@Composable
private fun NewEntryCard(state: JournalUiState, speechReady: Boolean, actions: JournalActions) {
    val recorder = state.recorder
    val recording = recorder is RecorderState.Recording
    val level = (recorder as? RecorderState.Recording)?.level ?: 0f
    val animatedLevel by animateFloatAsState(targetValue = level, label = "journal mic level")
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(88.dp)) {
                if (recording) {
                    Box(
                        Modifier
                            .size(64.dp)
                            .graphicsLayer {
                                val scale = 1f + animatedLevel * 0.4f
                                scaleX = scale
                                scaleY = scale
                            }
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f), CircleShape),
                    )
                }
                if (recorder == RecorderState.Transcribing || state.working != null) {
                    CircularProgressIndicator(Modifier.size(56.dp))
                } else {
                    FilledIconButton(
                        onClick = actions.onRecord,
                        enabled = speechReady,
                        modifier = Modifier.size(64.dp),
                        colors = if (recording) {
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError,
                            )
                        } else {
                            IconButtonDefaults.filledIconButtonColors()
                        },
                    ) {
                        Icon(
                            painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
                            contentDescription = if (recording) "Stop" else "Record an entry",
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when {
                        !speechReady -> "Set up the speech model on the Dictate page first"
                        recorder is RecorderState.Recording -> "%d:%02d  ·  tap to stop".format(
                            recorder.seconds.toInt() / 60,
                            recorder.seconds.toInt() % 60,
                        )
                        recorder == RecorderState.Transcribing -> "Transcribing…"
                        state.working != null -> state.working
                        else -> "New entry"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                if (recorder == RecorderState.Idle && state.working == null) {
                    Hint("Tap and talk for as long as you like. It's saved to today's page.")
                    TextButton(onClick = actions.onType, contentPadding = PaddingValues(0.dp)) { Text("Type an entry instead") }
                }
            }
        }
    }
}

@Composable
private fun FindCard(state: JournalUiState, aiReady: Boolean, actions: JournalActions) {
    var query by rememberSaveable { mutableStateOf("") }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(if (aiReady) "Search or ask your journal" else "Search your journal") },
                placeholder = { Text(if (aiReady) "What did I decide about the budget?" else "budget") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { actions.onSearch(query) }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { actions.onSearch(query) }, enabled = query.isNotBlank()) { Text("Search") }
                if (aiReady) {
                    OutlinedButton(onClick = { actions.onAsk(query) }, enabled = query.isNotBlank() && !state.asking) {
                        Text("Ask")
                    }
                }
                if (state.results != null || state.answer != null) {
                    TextButton(onClick = {
                        query = ""
                        actions.onClear()
                    }) { Text("Clear") }
                }
            }
            if (aiReady && state.results == null && state.answer == null && !state.asking) {
                Hint("Search finds words. Ask has the AI on this phone read the entries that match and answer, so it can take several seconds.")
            }
            if (state.asking) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Hint("Reading your journal…")
            }
            state.answer?.let { AnswerView(it, actions) }
            state.results?.let { ResultsView(state.query, it, actions) }
        }
    }
}

@Composable
private fun AnswerView(answer: JournalAnswer, actions: JournalActions) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(answer.text, style = MaterialTheme.typography.bodyLarge)
        if (answer.sources.isNotEmpty()) {
            Hint("Based on:")
            for (source in answer.sources.takeLast(MAX_SOURCES_SHOWN)) {
                TextButton(onClick = { actions.onOpen(source.date, source.entry) }, contentPadding = PaddingValues(0.dp)) {
                    Text(JournalAsk.label(source), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (answer.sources.size > MAX_SOURCES_SHOWN) Hint("and ${answer.sources.size - MAX_SOURCES_SHOWN} more")
        }
    }
}

private const val MAX_SOURCES_SHOWN = 6

@Composable
private fun ResultsView(query: String, results: List<JournalHit>, actions: JournalActions) {
    if (results.isEmpty()) {
        Hint("Nothing found for \"$query\".")
        return
    }
    Hint(if (results.size == 1) "1 entry" else "${results.size} entries")
    for (hit in results) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { actions.onOpen(hit.date, hit.entry) }
                .padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(dayLabel(hit.date) + "  ·  " + hit.entry.label, style = MaterialTheme.typography.labelLarge)
            Text(hit.snippet, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun ShareCard(actions: JournalActions) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Share with Claude", style = MaterialTheme.typography.titleMedium)
            Hint("Sends entries as one Markdown file to any app. Pick Claude to have it look across your journal.")
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { actions.onShareRecent(7) }) { Text("Last 7 days") }
                OutlinedButton(onClick = { actions.onShareRecent(30) }) { Text("Last 30 days") }
                OutlinedButton(onClick = { actions.onShareRecent(null) }) { Text("Everything") }
            }
        }
    }
}

@Composable
private fun DayCard(day: JournalDay, actions: JournalActions) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(day.date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { actions.onShareDay(day) }) { Text("Share") }
            }
            if (day.intro.isNotBlank()) {
                Text(day.intro, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            for (entry in day.entries) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clickable { actions.onOpen(day.date, entry) }
                        .padding(top = 6.dp, bottom = 6.dp, end = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    if (entry.label.isNotEmpty()) Text(entry.label, style = MaterialTheme.typography.labelLarge)
                    Text(entry.body, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                    if (entry.tags.isNotEmpty()) Hint(entry.tags.joinToString("  ") { "#$it" })
                }
            }
        }
    }
}

@Composable
private fun JournalSettingsCard(where: String, aiReady: Boolean, labels: Boolean, actions: JournalActions) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Journal settings", style = MaterialTheme.typography.titleMedium)
            Hint("Saved in: $where. One file per day, named like 2026-11-03.md.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = actions.onChooseFolder) { Text("Move to a folder") }
                if (where != "Inside this app") TextButton(onClick = actions.onKeepPrivate) { Text("Keep inside the app") }
            }
            if (aiReady) {
                SettingSwitch(
                    title = "Titles and tags by AI",
                    description = "After each entry is saved, the AI on this phone adds a short title and a few " +
                        "tags, which make it easier to find (here and for Claude).",
                    checked = labels,
                    onChange = actions.onLabels,
                )
            }
        }
    }
}

/** A new entry to type, or an existing one to edit (with Delete). */
@Composable
internal fun EntryDialog(
    heading: String,
    entry: JournalEntry?,
    onDismiss: () -> Unit,
    onSave: (title: String, body: String, tags: String) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var title by remember { mutableStateOf(entry?.title.orEmpty()) }
    var body by remember { mutableStateOf(entry?.body.orEmpty()) }
    var tags by remember { mutableStateOf(entry?.tags?.joinToString(", ").orEmpty()) }
    var confirmDelete by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = body,
                    onValueChange = { body = it },
                    label = { Text("Entry") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = tags,
                    onValueChange = { tags = it },
                    label = { Text("Tags (optional, separated by commas)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (onDelete != null) {
                    TextButton(onClick = { if (confirmDelete) onDelete() else confirmDelete = true }) {
                        Text(
                            if (confirmDelete) "Tap again to delete this entry" else "Delete entry",
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(title, body, tags) }, enabled = body.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.US)
private val DAY_YEAR_FORMAT = DateTimeFormatter.ofPattern("EEEE, MMM d, yyyy", Locale.US)

/** "Today", "Yesterday", "Monday, Nov 3", or with the year if it isn't this year. */
internal fun dayLabel(date: LocalDate): String {
    val today = LocalDate.now()
    return when {
        date == today -> "Today"
        date == today.minusDays(1) -> "Yesterday"
        date.year == today.year -> date.format(DAY_FORMAT)
        else -> date.format(DAY_YEAR_FORMAT)
    }
}
