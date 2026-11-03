package io.github.dbyoder.transcribe.ui

import android.Manifest
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dbyoder.transcribe.AiModelState
import io.github.dbyoder.transcribe.JournalViewModel
import io.github.dbyoder.transcribe.MainViewModel
import io.github.dbyoder.transcribe.ModelState
import io.github.dbyoder.transcribe.R
import io.github.dbyoder.transcribe.RecorderState
import io.github.dbyoder.transcribe.SetupState
import io.github.dbyoder.transcribe.UiState
import io.github.dbyoder.transcribe.ai.FormatModelStore
import io.github.dbyoder.transcribe.bubble.DictationService
import io.github.dbyoder.transcribe.core.FormatStyle
import io.github.dbyoder.transcribe.core.JournalEntry
import io.github.dbyoder.transcribe.history.Transcript
import io.github.dbyoder.transcribe.model.ModelStore
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun MainScreen(viewModel: MainViewModel, journal: JournalViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val journalState by journal.state.collectAsStateWithLifecycle()
    val journalDays by journal.days.collectAsStateWithLifecycle()
    val journalLoading by journal.loading.collectAsStateWithLifecycle()
    val journalWhere by journal.where.collectAsStateWithLifecycle()
    var typingEntry by remember { mutableStateOf(false) }
    var editingEntry by remember { mutableStateOf<Pair<LocalDate, JournalEntry>?>(null) }
    var journalAfterGrant by remember { mutableStateOf(false) }
    val history by viewModel.history.collectAsStateWithLifecycle()
    val words by viewModel.words.collectAsStateWithLifecycle()
    val snippets by viewModel.snippets.collectAsStateWithLifecycle()
    var addingWord by remember { mutableStateOf(false) }
    var addingSnippet by remember { mutableStateOf(false) }
    var fixing by remember { mutableStateOf<Transcript?>(null) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var recordAfterGrant by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val aiImportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importAiModel(uri)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) journal.useFolder(uri)
    }
    val contactsPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.setUseContacts(true)
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "Transcribe needs contacts access to listen out for names.",
                    actionLabel = "Settings",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) openAppInfo(context)
            }
        }
    }
    val onUseContacts = { on: Boolean ->
        if (on && context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            contactsPermissionLauncher.launch(Manifest.permission.READ_CONTACTS)
        } else {
            viewModel.setUseContacts(on)
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshSetup()
        if (granted) {
            if (recordAfterGrant) viewModel.toggleRecording()
            if (journalAfterGrant) journal.toggleRecording()
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "Microphone access is needed to dictate.",
                    actionLabel = "Settings",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) openAppInfo(context)
            }
        }
        recordAfterGrant = false
        journalAfterGrant = false
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.messageShown()
    }
    LaunchedEffect(journalState.message) {
        val message = journalState.message ?: return@LaunchedEffect
        journal.messageShown()
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
    }
    // Hand an exported journal file to the share sheet (Claude, email, Drive...).
    LaunchedEffect(journalState.share) {
        val share = journalState.share ?: return@LaunchedEffect
        val send = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_STREAM, share.uri)
            .putExtra(Intent.EXTRA_SUBJECT, share.title)
            .putExtra(Intent.EXTRA_TITLE, share.title)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        send.clipData = ClipData.newRawUri(share.title, share.uri)
        runCatching { context.startActivity(Intent.createChooser(send, "Share your journal")) }
        journal.shareHandled()
    }

    val onDownload = {
        try {
            uriHandler.openUri(ModelStore.RECOMMENDED_MODEL_URL)
        } catch (e: Exception) {
            viewModel.showMessage("Couldn't open a browser. The download link is in the project README.")
        }
    }
    val onImport = { importLauncher.launch(arrayOf("*/*")) }
    val onAllowMic = {
        recordAfterGrant = false
        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val onMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleRecording()
        } else {
            recordAfterGrant = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val onJournalMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            journal.toggleRecording()
        } else {
            journalAfterGrant = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    val journalActions = JournalActions(
        onChooseFolder = { runCatching { folderPicker.launch(null) } },
        onKeepPrivate = journal::usePrivate,
        onRecord = onJournalMic,
        onType = { typingEntry = true },
        onSearch = journal::search,
        onAsk = journal::ask,
        onClear = journal::clearResults,
        onOpen = { date, entry -> editingEntry = date to entry },
        onShareDay = journal::shareDay,
        onShareRecent = journal::shareRecent,
        onLabels = journal::setLabels,
    )

    var tab by rememberSaveable { mutableStateOf(Tab.DICTATE) }
    val setup = state.setup
    val bubbleReady = setup.bubbleEnabled && setup.micGranted && !setup.shortcutButtonOn

    val modelCard = @Composable {
        ModelCard(
            model = state.model,
            threads = state.threads,
            idle = state.recorder == RecorderState.Idle,
            onDownload = onDownload,
            onImport = onImport,
            onThreads = viewModel::setThreads,
        )
    }
    val bubbleCard = @Composable {
        BubbleCard(
            setup = setup,
            onAllowMic = onAllowMic,
            onOpenAccessibility = { openBubbleSettings(context) },
            onOpenAppInfo = { openAppInfo(context) },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                for (t in Tab.entries) {
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(painterResource(t.icon), contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.DICTATE -> Page(padding) {
                item { Header("Transcribe", "Speech to text that runs entirely on this phone.") }
                // Until everything works, the next setup step comes first.
                when {
                    state.model !is ModelState.Ready -> item { modelCard() }
                    !bubbleReady -> item { bubbleCard() }
                    else -> item { ReadyCard(aiReady = state.ai is AiModelState.Ready) }
                }
                item { MicSection(state, onMic) }
                if (history.isNotEmpty()) {
                    item {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Recent dictations", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = viewModel::clearHistory) { Text("Clear") }
                        }
                    }
                }
                items(history, key = { it.id }) { transcript ->
                    TranscriptCard(
                        transcript = transcript,
                        onCopy = { clipboard.setText(AnnotatedString(transcript.text)) },
                        canFormat = state.ai is AiModelState.Ready && state.formattingId == null,
                        formatting = state.formattingId == transcript.id,
                        onFormat = { style -> viewModel.format(transcript, style) },
                        onFix = { fixing = transcript },
                        onSaveToJournal = { journal.addFromHistory(transcript) },
                    )
                }
            }

            Tab.JOURNAL -> JournalPage(
                padding = padding,
                where = journalWhere,
                loading = journalLoading,
                days = journalDays,
                state = journalState,
                speechReady = state.model is ModelState.Ready,
                aiReady = state.ai is AiModelState.Ready,
                actions = journalActions,
            )

            Tab.WORDS -> Page(padding) {
                item { Header("Words", "Names and phrases for dictation to get right, and text you insert by voice.") }
                item {
                    NamesCard(
                        useContacts = state.useContacts,
                        contactsAllowed = state.contactsAllowed,
                        contactNameCount = state.contactNameCount,
                        onUseContacts = onUseContacts,
                        hasOwnWords = words.isNotEmpty(),
                        boost = state.boostWords,
                        onBoost = viewModel::setBoostWords,
                    )
                }
                item { YourWordsCard(words = words, onAdd = { addingWord = true }, onRemove = viewModel::removeWord) }
                item {
                    SnippetsCard(
                        snippets = snippets,
                        onAdd = { addingSnippet = true },
                        onRemove = viewModel::removeSnippet,
                    )
                }
            }

            Tab.AI -> Page(padding) {
                item { Header("AI", "Optional rewriting, with an AI model that also runs on this phone.") }
                item {
                    AiModelCard(
                        ai = state.ai,
                        onDownload = { openLink(uriHandler, FormatModelStore.RECOMMENDED_MODEL_URL, viewModel) },
                        onOpenPage = { openLink(uriHandler, FormatModelStore.RECOMMENDED_MODEL_PAGE, viewModel) },
                        onImport = { aiImportLauncher.launch(arrayOf("*/*")) },
                        onRemove = viewModel::removeAiModel,
                    )
                }
                if (state.ai is AiModelState.Ready) {
                    item { BubbleButtonsCard(bubbleStyles = state.bubbleStyles, onBubbleStyle = viewModel::setBubbleStyleShown) }
                    item {
                        AiExtrasCard(
                            commandMode = state.commandMode,
                            onCommandMode = viewModel::setCommandMode,
                            autoPolish = state.autoPolish,
                            onAutoPolish = viewModel::setAutoPolish,
                        )
                    }
                }
            }

            Tab.SETTINGS -> Page(padding) {
                item { Header("Settings", null) }
                item { CleanUpCard(enabled = state.cleanUp, onChange = viewModel::setCleanUp) }
                item { VoiceCommandsCard() }
                if (state.model is ModelState.Ready) item { bubbleCard() }
                item { modelCard() }
                item { AboutCard() }
            }
        }
    }

    if (addingWord) {
        AddWordDialog(
            onDismiss = { addingWord = false },
            onSave = { word, heardAs ->
                viewModel.addWord(word, heardAs)
                addingWord = false
            },
        )
    }
    if (addingSnippet) {
        AddSnippetDialog(
            onDismiss = { addingSnippet = false },
            onSave = { trigger, text ->
                viewModel.addSnippet(trigger, text)
                addingSnippet = false
            },
        )
    }
    if (typingEntry) {
        EntryDialog(
            heading = "New entry",
            entry = null,
            onDismiss = { typingEntry = false },
            onSave = { title, body, tags ->
                journal.addTyped(title, body, tags)
                typingEntry = false
            },
            onDelete = null,
        )
    }
    editingEntry?.let { (date, entry) ->
        EntryDialog(
            heading = dayLabel(date) + (entry.time?.let { "  ·  " + io.github.dbyoder.transcribe.core.JournalMarkdown.timeLabel(it) }.orEmpty()),
            entry = entry,
            onDismiss = { editingEntry = null },
            onSave = { title, body, tags ->
                journal.updateEntry(date, entry, title, body, tags)
                editingEntry = null
            },
            onDelete = {
                journal.deleteEntry(date, entry)
                editingEntry = null
            },
        )
    }
    fixing?.let { transcript ->
        FixWordDialog(
            transcript = transcript,
            onDismiss = { fixing = null },
            onSave = { wrong, right ->
                viewModel.fixWord(transcript, wrong, right)
                fixing = null
            },
        )
    }
}

/** The app's pages, picked from the bar at the bottom. */
private enum class Tab(val label: String, val icon: Int) {
    DICTATE("Dictate", R.drawable.ic_mic),
    JOURNAL("Journal", R.drawable.ic_book),
    WORDS("Words", R.drawable.ic_text_fields),
    AI("AI", R.drawable.ic_command),
    SETTINGS("Settings", R.drawable.ic_settings),
}

/** One page: a scrolling column of cards, clear of the status bar and the bottom bar. */
@Composable
internal fun Page(padding: PaddingValues, content: LazyListScope.() -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = padding.calculateTopPadding() + 16.dp,
            bottom = padding.calculateBottomPadding() + 24.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        content = content,
    )
}

@Composable
internal fun Header(title: String, subtitle: String?) {
    Column {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        if (subtitle != null) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Shown on the Dictate page once everything is set up: how to use the bubble. */
@Composable
private fun ReadyCard(aiReady: Boolean) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Ready to dictate", style = MaterialTheme.typography.titleMedium)
            Hint("Tap into a text box in any app and the mic bubble appears above the keyboard.")
            HowTo("Tap", "Start. Tap again to stop.")
            HowTo("Hold", "Talk while you hold it, let go to finish.")
            HowTo("Drag", "Move it.")
            if (aiReady) HowTo("Select text", "It turns purple: say what to change.")
            Hint("More things you can say are under Settings.")
        }
    }
}

/** A gesture or phrase, and what it does. */
@Composable
private fun HowTo(action: String, effect: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(action, style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(96.dp).padding(top = 2.dp))
        Text(effect, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}

/** Everything you can say while dictating, in one place. */
@Composable
private fun VoiceCommandsCard() {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Things you can say", style = MaterialTheme.typography.titleMedium)
            Said("\"comma\", \"period\", \"question mark\", \"exclamation point\", \"colon\"", "Types the punctuation mark.")
            Said("\"open quote\" … \"close quote\", \"open paren\" … \"close paren\"", "Quotes and brackets.")
            Said("\"new line\", \"new paragraph\"", "A line break.")
            Said("\"scratch that\"", "Deletes the sentence you just said. Said first, it takes back your last dictation.")
            Said("\"thumbs up emoji\"", "Any emoji's name, then \"emoji\", types it: 👍")
            Said("…\"send\" at the end", "Taps the app's Send button. \"Press enter\" works too.")
            Said("\"insert my email\"", "Types one of your snippets (set them up under Words).")
            Hint("These are part of the tidy-up, so they work while it's on.")
        }
    }
}

@Composable
private fun Said(phrase: String, effect: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(phrase, style = MaterialTheme.typography.labelLarge)
        Hint(effect)
    }
}

@Composable
private fun AboutCard() {
    val context = LocalContext.current
    val version = remember {
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
    }
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("About", style = MaterialTheme.typography.titleMedium)
            Hint(if (version != null) "Transcribe $version" else "Transcribe")
            Hint(
                "Everything runs on this phone. The app has no internet access, so your voice, your text and " +
                    "your contacts can't leave it.",
            )
        }
    }
}

@Composable
private fun ModelCard(
    model: ModelState,
    threads: Int,
    idle: Boolean,
    onDownload: () -> Unit,
    onImport: () -> Unit,
    onThreads: (Int) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (model) {
                ModelState.Checking -> Text("Checking for the speech model…")

                ModelState.Missing -> {
                    Text("Set up the speech model", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "This app can't go online, so you download the model once yourself. " +
                            "It's a large file (several hundred MB), so Wi-Fi is best.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) {
                        Text("1. Download the model")
                    }
                    Hint("Opens in your browser. Wait for the download to finish.")
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) {
                        Text("2. Import the downloaded file")
                    }
                    Hint("Pick the file ending in .tar.bz2 from Downloads. You can delete it afterwards.")
                }

                is ModelState.Installing -> {
                    Text("Unpacking the speech model…", style = MaterialTheme.typography.titleMedium)
                    val fraction = model.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    Hint("This takes a minute or two. Keep the app open.")
                }

                ModelState.Loading -> {
                    Text("Loading the speech model…")
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }

                is ModelState.Ready -> {
                    Text(displayName(model.name), style = MaterialTheme.typography.titleMedium)
                    Hint("Loaded in %.1f s using %d CPU threads".format(model.loadMillis / 1000f, model.threads))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Threads", style = MaterialTheme.typography.bodyMedium)
                        for (n in listOf(2, 4, 6)) {
                            FilterChip(
                                selected = threads == n,
                                onClick = { onThreads(n) },
                                label = { Text("$n") },
                                enabled = idle,
                            )
                        }
                    }
                    TextButton(onClick = onImport, enabled = idle) { Text("Replace model") }
                }

                is ModelState.Failed -> {
                    Text(model.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("Import a model file") }
                    OutlinedButton(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("Download the model") }
                }
            }
        }
    }
}

@Composable
private fun BubbleCard(
    setup: SetupState,
    onAllowMic: () -> Unit,
    onOpenAccessibility: () -> Unit,
    onOpenAppInfo: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (setup.bubbleEnabled) {
                Text("Floating bubble is on", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Start typing in any app and a mic bubble appears above the keyboard. " +
                        "Tap it, speak, then tap again, or hold it down while you speak and let go. " +
                        "Drag it to move it. Long dictations are transcribed while you talk, so " +
                        "there's little wait at the end.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (!setup.micGranted) {
                    Text("The bubble needs microphone access.", color = MaterialTheme.colorScheme.error)
                    Button(onClick = onAllowMic, modifier = Modifier.fillMaxWidth()) { Text("Allow microphone") }
                }
                if (setup.shortcutButtonOn) {
                    Text(
                        "Android's accessibility shortcut is also on for Transcribe. That's the extra mic " +
                            "button that stays on screen everywhere. You don't need it: open the settings below " +
                            "and turn off \"Transcribe shortcut\" (keep \"Use Transcribe\" on).",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                        Text("Turn off the shortcut")
                    }
                } else {
                    TextButton(onClick = onOpenAccessibility) { Text("Turn off in accessibility settings") }
                }
            } else {
                Text("Turn on the floating bubble", style = MaterialTheme.typography.titleMedium)
                Text(
                    "It puts a mic button above your keyboard in any app, so you can dictate straight into the text box.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Hint(
                    "Android calls this an accessibility service and asks you to confirm, because it can see the " +
                        "screen. Transcribe only looks at the text box you're typing in, and it has no internet " +
                        "access, so nothing can leave the phone.",
                )
                if (!setup.micGranted) {
                    Button(onClick = onAllowMic, modifier = Modifier.fillMaxWidth()) { Text("1. Allow microphone") }
                }
                Button(onClick = onOpenAccessibility, modifier = Modifier.fillMaxWidth()) {
                    Text(if (setup.micGranted) "Turn on the bubble" else "2. Turn on the bubble")
                }
                Hint(
                    "Find Transcribe in the list (it may be under Downloaded apps) and switch on \"Use " +
                        "Transcribe\". Leave \"Transcribe shortcut\" off: the bubble appears by itself.",
                )
                Hint(
                    "If the switch is greyed out or says \"Restricted setting\": open App info, tap ⋮ in the top " +
                        "corner, choose Allow restricted settings, then try again.",
                )
                OutlinedButton(onClick = onOpenAppInfo, modifier = Modifier.fillMaxWidth()) { Text("Open App info") }
            }
        }
    }
}

@Composable
private fun CleanUpCard(enabled: Boolean, onChange: (Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Tidy up dictation", style = MaterialTheme.typography.titleMedium)
                Hint(
                    "Removes um, uh and stutters, drops fillers like \", you know,\", and turns the things " +
                        "you say below into punctuation and commands. Instant, no AI.",
                )
            }
            Switch(checked = enabled, onCheckedChange = onChange)
        }
    }
}

@Composable
private fun MicSection(state: UiState, onMic: () -> Unit) {
    val ready = state.model is ModelState.Ready
    val recorder = state.recorder
    val recording = recorder is RecorderState.Recording
    val level = (recorder as? RecorderState.Recording)?.level ?: 0f
    val animatedLevel by animateFloatAsState(targetValue = level, label = "mic level")

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(160.dp)) {
            if (recording) {
                // A ring that swells with your voice, so you can see the mic is hearing you.
                Box(
                    Modifier
                        .size(112.dp)
                        .graphicsLayer {
                            val scale = 1f + animatedLevel * 0.4f
                            scaleX = scale
                            scaleY = scale
                        }
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.25f), CircleShape),
                )
            }
            if (recorder == RecorderState.Transcribing) {
                CircularProgressIndicator(Modifier.size(96.dp), strokeWidth = 6.dp)
            } else {
                FilledIconButton(
                    onClick = onMic,
                    enabled = ready,
                    modifier = Modifier.size(112.dp),
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
                        painter = painterResource(if (recording) R.drawable.ic_stop else R.drawable.ic_mic),
                        contentDescription = if (recording) "Stop" else "Start dictating",
                        modifier = Modifier.size(48.dp),
                    )
                }
            }
        }
        Text(
            text = when {
                !ready -> "Set up the speech model to start"
                recorder is RecorderState.Recording -> "${formatDuration(recorder.seconds)}  ·  tap to stop"
                recorder == RecorderState.Transcribing -> "Transcribing…"
                else -> "Try it here: tap to dictate"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun TranscriptCard(
    transcript: Transcript,
    onCopy: () -> Unit,
    canFormat: Boolean,
    formatting: Boolean,
    onFormat: (FormatStyle) -> Unit,
    onFix: () -> Unit,
    onSaveToJournal: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 8.dp, end = 4.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                SelectionContainer(Modifier.weight(1f).padding(top = 12.dp)) {
                    Text(transcript.text, style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = onCopy) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy")
                }
            }
            val original = transcript.original
            if (original != null) {
                Text(
                    (if (transcript.style != null) "From: " else "Before tidy-up: ") + original,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
            Text(
                statsLine(transcript),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 12.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onFix) { Text("Fix a word") }
                TextButton(onClick = onSaveToJournal) { Text("Save to journal") }
            }
            if (formatting) {
                Column(Modifier.padding(top = 8.dp, end = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Hint("Formatting on the phone… the first one takes longer while the AI model loads.")
                }
            } else if (canFormat) {
                Row(
                    Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (style in FormatStyle.entries) {
                        SuggestionChip(onClick = { onFormat(style) }, label = { Text(style.label) })
                    }
                }
            }
        }
    }
}

@Composable
private fun AiModelCard(
    ai: AiModelState,
    onDownload: () -> Unit,
    onOpenPage: () -> Unit,
    onImport: () -> Unit,
    onRemove: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ai) {
                AiModelState.Missing -> {
                    Text("Set up the AI model (optional)", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Adds Polish, Bullets, Email, Shorter and Prompt buttons next to the bubble and on each " +
                            "dictation, plus command mode and Auto-polish. It uses Google's Gemma 4 model, which " +
                            "runs on this phone like everything else. It's a one-time 2.6 GB download, and each " +
                            "rewrite takes a few seconds.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onDownload, modifier = Modifier.fillMaxWidth()) { Text("1. Download the AI model") }
                    Hint("Opens in your browser; Wi-Fi recommended. If it doesn't start, open the model page and download the file ending in .litertlm.")
                    TextButton(onClick = onOpenPage) { Text("Open the model page") }
                    Button(onClick = onImport, modifier = Modifier.fillMaxWidth()) { Text("2. Import the downloaded file") }
                    Hint("Pick the .litertlm file from Downloads. You can delete the download afterwards.")
                }

                is AiModelState.Installing -> {
                    Text("Copying the AI model…", style = MaterialTheme.typography.titleMedium)
                    val fraction = ai.fraction
                    if (fraction == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    }
                    Hint("This takes a minute or two. Keep the app open.")
                }

                is AiModelState.Ready -> {
                    Text("AI model ready", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "After you dictate with the bubble, tap a button next to it to rewrite what you just said " +
                            "(Undo puts it back, ✕ puts the buttons away). Every button is also on each dictation " +
                            "on the Dictate page. The first rewrite takes longer while the model loads.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Hint("Model: ${ai.name}")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onImport) { Text("Replace") }
                        TextButton(onClick = onRemove) { Text("Remove (frees storage)") }
                    }
                }
            }
        }
    }
}

/** Which formatting buttons pop up beside the bubble. */
@Composable
private fun BubbleButtonsCard(bubbleStyles: Set<FormatStyle>, onBubbleStyle: (FormatStyle, Boolean) -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Buttons beside the bubble", style = MaterialTheme.typography.titleMedium)
            Hint("Pick the ones you use. They pop up for a few seconds after each dictation.")
            for (style in FormatStyle.entries) {
                StyleOption(
                    style = style,
                    checked = style in bubbleStyles,
                    onChange = { shown -> onBubbleStyle(style, shown) },
                )
            }
            if (bubbleStyles.isEmpty()) {
                Hint("None picked, so no buttons pop up after you dictate. You can still format dictations on the Dictate page.")
            }
        }
    }
}

@Composable
private fun AiExtrasCard(
    commandMode: Boolean,
    onCommandMode: (Boolean) -> Unit,
    autoPolish: Boolean,
    onAutoPolish: (Boolean) -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            SettingSwitch(
                title = "Command mode",
                description = "Select some text and the bubble turns purple. Tap or hold it and say what to do: " +
                    "\"make this friendlier\", \"translate to Spanish\", \"turn this into a list\". The result " +
                    "replaces the selection (Undo puts it back).",
                checked = commandMode,
                onChange = onCommandMode,
            )
            SettingSwitch(
                title = "Auto-polish",
                description = "Runs Polish on everything you dictate with the bubble before it's typed, so " +
                    "\"Tuesday, no wait, Wednesday\" comes out as \"Wednesday\". Adds a few seconds each time. " +
                    "Undo brings back your exact words.",
                checked = autoPolish,
                onChange = onAutoPolish,
            )
        }
    }
}

/** A titled on/off setting with an explanation. */
@Composable
internal fun SettingSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Hint(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun StyleOption(style: FormatStyle, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(12.dp))
        Column(Modifier.padding(start = 4.dp)) {
            Text(style.label, style = MaterialTheme.typography.bodyLarge)
            Hint(style.summary())
        }
    }
}

/** A one-line description of what each formatting button does. */
private fun FormatStyle.summary(): String = when (this) {
    FormatStyle.POLISH -> "Fixes grammar and punctuation, keeps your words"
    FormatStyle.BULLETS -> "Turns it into a list of key points"
    FormatStyle.EMAIL -> "Lays it out as an email, in your voice"
    FormatStyle.SHORTER -> "Cuts it to about half the length"
    FormatStyle.PROMPT -> "Turns it into clear instructions for an AI chat"
}

@Composable
internal fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8" → "parakeet-tdt-0.6b-v2-int8" */
private fun displayName(name: String): String = name.removePrefix("sherpa-onnx-").removePrefix("nemo-")

private fun formatDuration(seconds: Float): String {
    val total = seconds.toInt()
    return "%d:%02d".format(total / 60, total % 60)
}

private fun statsLine(t: Transcript): String {
    val processSeconds = t.processMillis / 1000f
    if (t.style != null) {
        return "${DateUtils.getRelativeTimeSpanString(t.createdAt)}  ·  ${t.style}  ·  formatted in %.1f s".format(processSeconds)
    }
    val speed = if (t.processMillis > 0) "  ·  %.0f× real time".format(t.audioSeconds / processSeconds) else ""
    val time = DateUtils.getRelativeTimeSpanString(t.createdAt)
    val via = t.insertedVia?.let { "  ·  $it" }.orEmpty()
    // Long dictations are mostly transcribed while you talk: show how long you actually waited.
    val wait = t.waitMillis?.takeIf { it < t.processMillis * 0.8 }?.let { "  ·  %.1f s wait".format(it / 1000f) }.orEmpty()
    return "$time  ·  %.1f s → %.2f s%s".format(t.audioSeconds, processSeconds, speed) + wait + via
}

private fun openLink(uriHandler: UriHandler, url: String, viewModel: MainViewModel) {
    try {
        uriHandler.openUri(url)
    } catch (e: Exception) {
        viewModel.showMessage("Couldn't open a browser. The link is in the project README.")
    }
}

private fun openAppInfo(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}

/** Opens Transcribe's own page in accessibility settings, or the general list if that's not allowed. */
private fun openBubbleSettings(context: Context) {
    val component = ComponentName(context, DictationService::class.java).flattenToString()
    val details = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
        .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
    try {
        context.startActivity(details)
    } catch (e: Exception) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
