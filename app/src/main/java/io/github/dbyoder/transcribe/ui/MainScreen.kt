package io.github.dbyoder.transcribe.ui

import android.Manifest
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dbyoder.transcribe.MainViewModel
import io.github.dbyoder.transcribe.ModelState
import io.github.dbyoder.transcribe.R
import io.github.dbyoder.transcribe.RecorderState
import io.github.dbyoder.transcribe.SetupState
import io.github.dbyoder.transcribe.UiState
import io.github.dbyoder.transcribe.bubble.DictationService
import io.github.dbyoder.transcribe.history.Transcript
import io.github.dbyoder.transcribe.model.ModelStore
import kotlinx.coroutines.launch

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var recordAfterGrant by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        viewModel.refreshSetup()
        if (granted) {
            if (recordAfterGrant) viewModel.toggleRecording()
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
    }

    LaunchedEffect(state.message) {
        val message = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(message, withDismissAction = true, duration = SnackbarDuration.Long)
        viewModel.messageShown()
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

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding() + 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { Header() }
            item {
                ModelCard(
                    model = state.model,
                    threads = state.threads,
                    idle = state.recorder == RecorderState.Idle,
                    onDownload = onDownload,
                    onImport = onImport,
                    onThreads = viewModel::setThreads,
                )
            }
            if (state.model is ModelState.Ready) {
                item {
                    BubbleCard(
                        setup = state.setup,
                        onAllowMic = onAllowMic,
                        onOpenAccessibility = { openBubbleSettings(context) },
                        onOpenAppInfo = { openAppInfo(context) },
                    )
                }
                item { CleanUpCard(enabled = state.cleanUp, onChange = viewModel::setCleanUp) }
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
                TranscriptCard(transcript, onCopy = { clipboard.setText(AnnotatedString(transcript.text)) })
            }
        }
    }
}

@Composable
private fun Header() {
    Column {
        Text("Transcribe", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Speech to text that runs entirely on this phone. The app has no internet access.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
                        "Tap it, speak, then tap again. Drag it to move it.",
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
                    "Removes um, uh and stutters, drops fillers like \", you know,\", and turns a spoken " +
                        "\"new line\" or \"new paragraph\" into a line break. Instant, no AI.",
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
private fun TranscriptCard(transcript: Transcript, onCopy: () -> Unit) {
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
                    "Before tidy-up: $original",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 12.dp, bottom = 4.dp),
                )
            }
            Text(
                statsLine(transcript),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Hint(text: String) {
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
    val speed = if (t.processMillis > 0) "  ·  %.0f× real time".format(t.audioSeconds / processSeconds) else ""
    val time = DateUtils.getRelativeTimeSpanString(t.createdAt)
    val via = t.insertedVia?.let { "  ·  $it" }.orEmpty()
    return "$time  ·  %.1f s → %.2f s%s".format(t.audioSeconds, processSeconds, speed) + via
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
