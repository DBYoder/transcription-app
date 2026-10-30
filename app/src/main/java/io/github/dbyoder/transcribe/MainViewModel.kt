package io.github.dbyoder.transcribe

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.dbyoder.transcribe.audio.AudioRecorder
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.core.TransducerModel
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
    data class Loading(val name: String) : ModelState
    data class Ready(val name: String, val loadMillis: Long, val threads: Int) : ModelState
    data class Failed(val message: String) : ModelState
}

sealed interface RecorderState {
    data object Idle : RecorderState
    data class Recording(val seconds: Float, val level: Float) : RecorderState
    data object Transcribing : RecorderState
}

data class Transcript(
    val id: Long,
    val text: String,
    val audioSeconds: Float,
    val processMillis: Long,
)

data class UiState(
    val model: ModelState = ModelState.Checking,
    val recorder: RecorderState = RecorderState.Idle,
    val transcripts: List<Transcript> = emptyList(),
    val threads: Int = DEFAULT_THREADS,
    /** A one-off notice for the snackbar; cleared with [MainViewModel.messageShown]. */
    val message: String? = null,
)

const val DEFAULT_THREADS = 4
const val MAX_RECORDING_SECONDS = 180
private const val MIN_RECORDING_SECONDS = 0.3f
private const val KEY_THREADS = "threads"

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = ModelStore(app)
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val recorder = AudioRecorder(MAX_RECORDING_SECONDS)
    private var importJob: Job? = null
    private var nextId = 0L

    private val _state = MutableStateFlow(UiState(threads = prefs.getInt(KEY_THREADS, DEFAULT_THREADS)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { loadInstalledModel() }
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
                val model = store.import(uri) { fraction ->
                    val percent = fraction?.let { (it * 100).roundToInt() } ?: -1
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _state.update { it.copy(model = ModelState.Installing(fraction)) }
                    }
                }
                SpeechEngine.unload() // the files changed, so force a fresh load
                loadModel(model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(message = e.message ?: "Couldn't import that file") }
                loadInstalledModel() // fall back to whatever was installed before
            }
        }
    }

    fun setThreads(threads: Int) {
        val current = _state.value
        if (threads == current.threads || current.recorder != RecorderState.Idle) return
        prefs.edit().putInt(KEY_THREADS, threads).apply()
        _state.update { it.copy(threads = threads) }
        if (current.model is ModelState.Ready) {
            viewModelScope.launch { loadInstalledModel() }
        }
    }

    private suspend fun loadInstalledModel() {
        val model = store.installed()
        if (model == null) {
            _state.update { it.copy(model = ModelState.Missing) }
        } else {
            loadModel(model)
        }
    }

    private suspend fun loadModel(model: TransducerModel) {
        val threads = _state.value.threads
        _state.update { it.copy(model = ModelState.Loading(model.name)) }
        try {
            val millis = measureTimeMillis { SpeechEngine.load(model, threads) }
            _state.update { it.copy(model = ModelState.Ready(model.name, millis, threads)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(model = ModelState.Failed("Couldn't load the speech model: ${e.message}")) }
        }
    }

    // --- Recording ------------------------------------------------------------------------

    /** Called when the mic button is tapped (after the microphone permission is granted). */
    fun toggleRecording() {
        when (_state.value.recorder) {
            RecorderState.Idle -> startRecording()
            is RecorderState.Recording -> viewModelScope.launch { finishRecording() }
            RecorderState.Transcribing -> Unit
        }
    }

    /** The app went off screen. Android stops giving background apps mic audio, so wrap up now. */
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
            val transcript = Transcript(nextId++, text, seconds, millis)
            _state.update { it.copy(recorder = RecorderState.Idle, transcripts = listOf(transcript) + it.transcripts) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(recorder = RecorderState.Idle, message = "Transcription failed: ${e.message}") }
        }
    }

    // --- Misc -----------------------------------------------------------------------------

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
