package io.github.dbyoder.transcribe.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.dbyoder.transcribe.MainViewModel
import io.github.dbyoder.transcribe.ModelState
import io.github.dbyoder.transcribe.R
import io.github.dbyoder.transcribe.RecorderState
import io.github.dbyoder.transcribe.Transcript
import io.github.dbyoder.transcribe.UiState
import io.github.dbyoder.transcribe.model.ModelStore
import kotlinx.coroutines.launch

@Composable
fun MainScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboardManager.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) viewModel.importModel(uri)
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            viewModel.toggleRecording()
        } else {
            scope.launch {
                val result = snackbar.showSnackbar(
                    message = "Microphone access is needed to dictate.",
                    actionLabel = "Settings",
                    duration = SnackbarDuration.Long,
                )
                if (result == SnackbarResult.ActionPerformed) openAppSettings(context)
            }
        }
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
    val onMic = {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            viewModel.toggleRecording()
        } else {
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
            item { MicSection(state, onMic) }
            if (state.transcripts.isNotEmpty()) {
                item { Text("Results", style = MaterialTheme.typography.titleMedium) }
            }
            items(state.transcripts, key = { it.id }) { transcript ->
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

                is ModelState.Loading -> {
                    Text("Loading ${displayName(model.name)}…")
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
                else -> "Tap to dictate"
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
                    if (transcript.text.isBlank()) {
                        Text(
                            "(No speech detected)",
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(transcript.text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
                IconButton(onClick = onCopy, enabled = transcript.text.isNotBlank()) {
                    Icon(painterResource(R.drawable.ic_copy), contentDescription = "Copy")
                }
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
    val speed = if (t.processMillis > 0) "  ·  %.0f× faster than real time".format(t.audioSeconds / processSeconds) else ""
    return "%.1f s of speech → %.2f s to transcribe%s".format(t.audioSeconds, processSeconds, speed)
}

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
    )
}
