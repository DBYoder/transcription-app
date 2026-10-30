package io.github.dbyoder.transcribe

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dbyoder.transcribe.ai.FormatModelStore
import io.github.dbyoder.transcribe.ai.Formatter
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.audio.MAX_RECORDING_SECONDS
import io.github.dbyoder.transcribe.audio.MIN_RECORDING_SECONDS
import io.github.dbyoder.transcribe.bubble.DictationService
import io.github.dbyoder.transcribe.core.FormatStyle
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.history.Transcript
import io.github.dbyoder.transcribe.history.TranscriptHistory
import io.github.dbyoder.transcribe.model.ModelStore
import io.github.dbyoder.transcribe.speech.SpeechEngine
import io.github.dbyoder.transcribe.speech.TextPipeline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import kotlin.system.measureTimeMillis

sealed interface ModelState {
    data object Checking : ModelState
    data object Missing : ModelState

    /** @param fraction 0..1, or null when the file size is unknown. */
    data class Installing(val fraction: Float?) : ModelState
    data object Loading : ModelState
    data class Ready(val name: String, val loadMillis: Long, val threads: Int) : ModelState
    data class Failed(val message: String) : ModelState
}

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val seconds: Float, val level: Float) : RecorderState
    data object Transcribing : RecorderState
}

/** The optional AI model behind the formatting buttons. */
sealed interface AiModelState {
    data object Missing : AiModelState

    /** @param fraction 0..1, or null when the file size is unknown. */
    data class Installing(val fraction: Float?) : AiModelState
    data class Ready(val name: String) : AiModelState
}

/** What's still needed for the floating bubble to work. */
data class SetupState(
    val micGranted: Boolean = false,
    val bubbleEnabled: Boolean = false,
    /** Android's extra floating accessibility button is on for Transcribe (not needed). */
    val shortcutButtonOn: Boolean = false,
)

data class UiState(
    val model: ModelState = ModelState.Checking,
    val recorder: RecorderState = RecorderState.Idle,
    val threads: Int = DEFAULT_THREADS,
    val cleanUp: Boolean = true,
    val setup: SetupState = SetupState(),
    val ai: AiModelState = AiModelState.Missing,
    /** The history item an AI formatting button is working on, if any. */
    val formattingId: Long? = null,
    /** A one-off notice for the snackbar; cleared with [MainViewModel.messageShown]. */
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app)
    private val aiStore = FormatModelStore(app)
    private var aiImportJob: Job? = null
    private val recorder = AudioRecorder(MAX_RECORDING_SECONDS)
    private var importJob: Job? = null

    private val _state = MutableStateFlow(
        UiState(threads = AppSettings.threads(app), cleanUp = AppSettings.cleanUp(app)),
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Recent dictations from both the bubble and the button on this screen. */
    val history: StateFlow<List<Transcript>> = TranscriptHistory.items

    init {
        refreshSetup()
        refreshAi()
        viewModelScope.launch { loadModel() }
    }

    /** Re-checks permissions and the bubble switch (call when the screen comes back). */
    fun refreshSetup() {
        val app = getApplication<Application>()
        val setup = SetupState(
            micGranted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            bubbleEnabled = DictationService.isEnabled(app),
            shortcutButtonOn = DictationService.isShortcutButtonOn(app),
        )
        _state.update { it.copy(setup = setup) }
    }

    // --- Model ----------------------------------------------------------------------------

    fun importModel(uri: Uri) {
        val current = _state.value
        val busy = current.model is ModelState.Checking || current.model is ModelState.Loading
        if (busy || importJob?.isActive == true || current.recorder != RecorderState.Idle) return
        importJob = viewModelScope.launch {
            _state.update { it.copy(model = ModelState.Installing(null)) }
            try {
                var lastPercent = -1
                store.import(uri) { fraction ->
                    val percent = fraction?.let { (it * 100).roundToInt() } ?: -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _state.update { it.copy(model = ModelState.Installing(fraction)) }
                    }
                }
                SpeechEngine.unload() // the files changed, so load them fresh
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Couldn't import that file") }
            }
            loadModel() // the new model, or whatever was installed before if the import failed
        }
    }

    fun setThreads(threads: Int) {
        val current = _state.value
        if (threads == current.threads || current.recorder != RecorderState.Idle) return
        AppSettings.setThreads(getApplication(), threads)
        _state.update { it.copy(threads = threads) }
        if (current.model is ModelState.Ready) {
            viewModelScope.launch { loadModel() }
        }
    }

    private suspend fun loadModel() {
        _state.update { it.copy(model = ModelState.Loading) }
        try {
            val info = SpeechEngine.ensureLoaded()
            val model = if (info == null) ModelState.Missing else ModelState.Ready(info.modelName, info.loadMillis, info.threads)
            _state.update { it.copy(model = model) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(model = ModelState.Failed("Couldn't load the speech model: ${e.message}")) }
        }
    }

    // --- Recording on this screen -------------------------------------------------------------

    /** Called when the mic button is tapped (after the microphone permission is granted). */
    fun toggleRecording() {
        when (_state.value.recorder) {
            RecorderState.Idle -> startRecording()
            is RecorderState.Recording -> viewModelScope.launch { finishRecording() }
            RecorderState.Transcribing -> Unit
        }
    }

    /** The app went off screen. Android stops giving hidden apps mic audio, so wrap up now. */
    fun onAppHidden() {
        if (_state.value.recorder is RecorderState.Recording) {
            viewModelScope.launch { finishRecording() }
        }
    }

    private fun startRecording() {
        if (_state.value.model !is ModelState.Ready) return
        _state.update { it.copy(recorder = RecorderState.Recording(0f, 0f), message = null) }
        try {
            recorder.start(
                scope = viewModelScope,
                onProgress = { seconds, level ->
                    _state.update { s ->
                        if (s.recorder is RecorderState.Recording) s.copy(recorder = RecorderState.Recording(seconds, level)) else s
                    }
                },
                onLimitReached = {
                    viewModelScope.launch {
                        finishRecording()
                        _state.update { it.copy(message = "Stopped at the ${MAX_RECORDING_SECONDS / 60}-minute limit") }
                    }
                },
            )
        } catch (e: Exception) {
            _state.update { it.copy(recorder = RecorderState.Idle, message = e.message ?: "Couldn't start recording") }
        }
    }

    private suspend fun finishRecording() {
        if (_state.value.recorder !is RecorderState.Recording) return
        _state.update { it.copy(recorder = RecorderState.Transcribing) }
        val samples = recorder.stop()
        val seconds = samples.size / SAMPLE_RATE.toFloat()
        if (seconds < MIN_RECORDING_SECONDS) {
            _state.update { it.copy(recorder = RecorderState.Idle, message = "That was too short. Tap, speak, then tap again.") }
            return
        }
        try {
            var raw = ""
            val millis = measureTimeMillis { raw = SpeechEngine.transcribe(samples) }
            val text = TextPipeline.process(getApplication<Application>(), raw)
            if (text.isBlank()) {
                _state.update { it.copy(message = "No speech detected") }
            } else {
                TranscriptHistory.add(text, seconds, millis, original = raw)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(message = "Transcription failed: ${e.message}") }
        } finally {
            _state.update { it.copy(recorder = RecorderState.Idle) }
        }
    }

    // --- Misc -----------------------------------------------------------------------------

    // --- AI formatting ----------------------------------------------------------------------

    private fun refreshAi() {
        val ai = if (aiStore.installed() != null) AiModelState.Ready(aiStore.installedName() ?: "AI model") else AiModelState.Missing
        _state.update { it.copy(ai = ai) }
    }

    fun importAiModel(uri: Uri) {
        if (aiImportJob?.isActive == true || _state.value.formattingId != null) return
        aiImportJob = viewModelScope.launch {
            _state.update { it.copy(ai = AiModelState.Installing(null)) }
            try {
                Formatter.unload()
                aiStore.import(uri) { fraction -> _state.update { it.copy(ai = AiModelState.Installing(fraction)) } }
                _state.update { it.copy(message = "AI model ready. The formatting buttons are on.") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Couldn't import that file") }
            }
            refreshAi()
        }
    }

    fun removeAiModel() {
        if (_state.value.formattingId != null) return
        viewModelScope.launch {
            Formatter.unload()
            withContext(Dispatchers.IO) { aiStore.remove() }
            refreshAi()
        }
    }

    /** Rewrites a saved dictation with an AI formatting button; the result is added and copied. */
    fun format(transcript: Transcript, style: FormatStyle) {
        if (_state.value.formattingId != null || _state.value.ai !is AiModelState.Ready) return
        _state.update { it.copy(formattingId = transcript.id) }
        viewModelScope.launch {
            try {
                var formatted = ""
                val millis = measureTimeMillis { formatted = Formatter.format(transcript.text, style) }
                val app = getApplication<Application>()
                AppSettings.markFormatUsed(app)
                TranscriptHistory.add(formatted, 0f, millis, original = transcript.text, style = style.label)
                app.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("Dictation", formatted))
                _state.update { it.copy(message = "${style.label} version copied and added at the top") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = "Formatting failed: ${e.message}") }
            } finally {
                _state.update { it.copy(formattingId = null) }
            }
        }
    }

    fun setCleanUp(enabled: Boolean) {
        AppSettings.setCleanUp(getApplication<Application>(), enabled)
        _state.update { it.copy(cleanUp = enabled) }
    }

    fun clearHistory() = TranscriptHistory.clear()

    fun showMessage(message: String) {
        _state.update { it.copy(message = message) }
    }

    fun messageShown() {
        _state.update { it.copy(message = null) }
    }

    override fun onCleared() {
        recorder.cancel()
    }
}
