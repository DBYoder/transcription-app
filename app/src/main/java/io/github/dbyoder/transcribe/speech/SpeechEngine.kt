package io.github.dbyoder.transcribe.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.model.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.system.measureTimeMillis

/**
 * Runs the speech model with sherpa-onnx, entirely on the phone's CPU.
 *
 * One instance for the whole app process, shared by the app screen and the bubble. The
 * model takes a few seconds to load and several hundred MB of memory, so it loads on first
 * use and unloads after [IDLE_UNLOAD_MS] without use.
 */
object SpeechEngine {
    private const val IDLE_UNLOAD_MS = 10 * 60 * 1000L

    data class LoadInfo(val modelName: String, val threads: Int, val loadMillis: Long)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null
    private var loadInfo: LoadInfo? = null
    private var idleJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Loads the installed model with the current thread setting, if it isn't loaded already.
     * Returns null when no model is installed.
     */
    suspend fun ensureLoaded(): LoadInfo? = withContext(Dispatchers.Default) {
        mutex.withLock { loadLocked() }
    }

    /** Turns 16 kHz mono audio (floats in -1..1) into text, loading the model if needed. */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked() ?: throw IllegalStateException("No speech model is set up yet. Open Transcribe to add one.")
            val r = checkNotNull(recognizer)
            val stream = r.createStream()
            try {
                stream.acceptWaveform(samples, SAMPLE_RATE)
                r.decode(stream)
                r.getResult(stream).text.trim()
            } finally {
                stream.release()
            }
        }
    }

    /** Frees the model's memory. It reloads automatically on next use. */
    suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock { releaseLocked() }
    }

    private suspend fun loadLocked(): LoadInfo? {
        val model = ModelStore(appContext).installed() ?: return null
        val threads = AppSettings.threads(appContext)
        // lastModified changes when a model is re-imported, which forces a reload.
        val key = "${model.encoder.absolutePath}|${model.encoder.lastModified()}|$threads"
        if (recognizer == null || key != loadedKey) {
            releaseLocked()
            val config = OfflineRecognizerConfig(
                // Parakeet reads its real feature size from the model; 80 is just a default.
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    transducer = OfflineTransducerModelConfig(
                        encoder = model.encoder.absolutePath,
                        decoder = model.decoder.absolutePath,
                        joiner = model.joiner.absolutePath,
                    ),
                    tokens = model.tokens.absolutePath,
                    modelType = model.modelType,
                    numThreads = threads,
                    provider = "cpu",
                ),
                decodingMethod = "greedy_search",
            )
            val millis = measureTimeMillis { recognizer = OfflineRecognizer(config = config) }
            loadedKey = key
            loadInfo = LoadInfo(model.name, threads, millis)
        }
        scheduleIdleUnload()
        return loadInfo
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            unload()
        }
    }

    private fun releaseLocked() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
        loadInfo = null
    }
}
