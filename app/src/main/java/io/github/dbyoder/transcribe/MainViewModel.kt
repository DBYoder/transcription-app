package io.github.dbyoder.transcribe

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.audio.MAX_RECORDING_SECONDS
import io.github.dbyoder.transcribe.audio.MIN_RECORDING_SECONDS
import io.github.dbyoder.transcribe.bubble.DictationService
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.history.Transcript
import io.github.dbyoder.transcribe.history.TranscriptHistory
import io.github.dbyoder.transcribe.model.ModelStore
import io.github.dbyoder.transcribe.speech.SpeechEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

/** What's still needed for the floating bubble to work. */
data class SetupState(
    val micGranted: Boolean = false,
    val bubbleEnabled: Boolean = false,
)

data class UiState(
    val model: ModelState = ModelState.Checking,
    val recorder: RecorderState = RecorderState.Idle,
    val threads: Int = DEFAULT_THREADS,
    val setup: SetupState = SetupState(),
    /** A one-off notice for the snackbar; cleared with [MainViewModel.messageShown]. */
    val message: String? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app)
    private val recorder = AudioRecorder(MAX_RECORDING_SECONDS)
    private var importJob: Job? = null

    private val _state = MutableStateFlow(UiState(threads = AppSettings.threads(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Recent dictations from both the bubble and the button on this screen. */
    val history: StateFlow<List<Transcript>> = TranscriptHistory.items

    init {
        refreshSetup()
        viewModelScope.launch { loadModel() }
    }

    /** Re-checks permissions and the bubble switch (call when the screen comes back). */
    fun refreshSetup() {
        val app = getApplication<Application>()
        val setup = SetupState(
            micGranted = app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED,
            bubbleEnabled = DictationService.isEnabled(app),
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
            var text = ""
            val millis = measureTimeMillis { text = SpeechEngine.transcribe(samples) }
            if (text.isBlank()) {
                _state.update { it.copy(message = "No speech detected") }
            } else {
                TranscriptHistory.add(text, seconds, millis)
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
