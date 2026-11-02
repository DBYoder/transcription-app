package io.github.dbyoder.transcribe.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.dbyoder.transcribe.core.DictionaryEntry
import io.github.dbyoder.transcribe.core.Snippet
import io.github.dbyoder.transcribe.history.Transcript
import kotlin.math.max
import kotlin.math.min

/** Your words: names and terms spelled your way. */
@Composable
internal fun WordsCard(
    words: List<DictionaryEntry>,
    boost: Boolean,
    onBoost: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onRemove: (DictionaryEntry) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Your words", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Names and terms spelled your way. Add what the app writes instead (\"whisper flow\" for " +
                    "\"Wispr Flow\") and it's fixed every time. Or tap \"Fix a word\" on any dictation below.",
            )
            for (entry in words) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(entry.word, style = MaterialTheme.typography.bodyLarge)
                        if (entry.heardAs.isNotEmpty()) {
                            Hint("Fixes " + entry.heardAs.joinToString(", ") { "\"$it\"" })
                        }
                    }
                    TextButton(onClick = { onRemove(entry) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = onAdd) { Text("Add a word") }
            if (words.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Listen out for these words", style = MaterialTheme.typography.titleSmall)
                        Hint(
                            "Helps the speech model catch them in the first place, not just fix them " +
                                "afterwards. Dictation takes a little longer while this is on.",
                        )
                    }
                    Switch(checked = boost, onCheckedChange = onBoost)
                }
            }
        }
    }
}

/** Snippets: text you insert by voice. */
@Composable
internal fun SnippetsCard(snippets: List<Snippet>, onAdd: () -> Unit, onRemove: (Snippet) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Snippets", style = MaterialTheme.typography.titleMedium)
            Hint(
                "Text you insert by voice, like your email address or a sign-off. Say \"insert\" and the " +
                    "trigger (\"insert my email\"), or just the trigger on its own.",
            )
            for (snippet in snippets) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("\"${snippet.trigger}\"", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            snippet.text.replace('\n', ' '),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    TextButton(onClick = { onRemove(snippet) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = onAdd) { Text("Add a snippet") }
        }
    }
}

@Composable
internal fun AddWordDialog(onDismiss: () -> Unit, onSave: (word: String, heardAs: String) -> Unit) {
    var word by remember { mutableStateOf("") }
    var heard by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = word,
                    onValueChange = { word = it },
                    label = { Text("Spelled your way") },
                    placeholder = { Text("Wispr Flow") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = heard,
                    onValueChange = { heard = it },
                    label = { Text("What the app writes instead (optional)") },
                    placeholder = { Text("whisper flow") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint("Separate several with commas.")
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(word, heard) }, enabled = word.isNotBlank()) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun AddSnippetDialog(onDismiss: () -> Unit, onSave: (trigger: String, text: String) -> Unit) {
    var trigger by remember { mutableStateOf("") }
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add a snippet") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = trigger,
                    onValueChange = { trigger = it },
                    label = { Text("What you say") },
                    placeholder = { Text("my email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("What gets typed") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(trigger, text) }, enabled = trigger.isNotBlank() && text.isNotBlank()) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Pick the misheard word(s) in a dictation and say what they should have been. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FixWordDialog(transcript: Transcript, onDismiss: () -> Unit, onSave: (wrong: String, right: String) -> Unit) {
    val tokens = remember(transcript.text) { transcript.text.split(Regex("\\s+")).filter { it.isNotEmpty() } }
    var start by remember { mutableIntStateOf(-1) }
    var end by remember { mutableIntStateOf(-1) }
    var wrong by remember { mutableStateOf("") }
    var right by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Fix a word") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint(
                    "Tap the word it got wrong (tap a second one to pick the words in between too), then " +
                        "type what you said. It'll be fixed in every dictation from now on.",
                )
                FlowRow(
                    Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    tokens.forEachIndexed { i, token ->
                        FilterChip(
                            selected = start >= 0 && i in start..end,
                            onClick = {
                                if (start < 0 || start != end) {
                                    start = i
                                    end = i
                                } else {
                                    val first = min(start, i)
                                    val last = max(start, i)
                                    start = first
                                    end = last
                                }
                                wrong = tokens.subList(start, end + 1).joinToString(" ").trim { !it.isLetterOrDigit() }
                            },
                            label = { Text(token) },
                        )
                    }
                }
                OutlinedTextField(
                    value = wrong,
                    onValueChange = { wrong = it },
                    label = { Text("It wrote") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = right,
                    onValueChange = { right = it },
                    label = { Text("You said") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(wrong, right) },
                enabled = wrong.isNotBlank() && right.isNotBlank() && wrong.trim() != right.trim(),
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
