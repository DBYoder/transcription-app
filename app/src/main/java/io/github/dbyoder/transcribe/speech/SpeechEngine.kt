package io.github.dbyoder.transcribe.speech

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.core.TransducerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs the speech model with sherpa-onnx, entirely on the phone's CPU.
 *
 * One instance for the whole app process: the model takes a few seconds to load and
 * several hundred MB of memory, so it's loaded once and shared (the floating bubble in a
 * later phase will use it too).
 */
object SpeechEngine {
    private val mutex = Mutex()
    private var recognizer: OfflineRecognizer? = null
    private var loadedKey: String? = null

    /** Loads [model] using [threads] CPU threads. Does nothing if it's already loaded. */
    suspend fun load(model: TransducerModel, threads: Int) = withContext(Dispatchers.Default) {
        mutex.withLock {
            val key = "${model.encoder.absolutePath}|$threads"
            if (key == loadedKey && recognizer != null) return@withLock

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
            recognizer = OfflineRecognizer(config = config)
            loadedKey = key
        }
    }

    /** Turns 16 kHz mono audio (floats in -1..1) into text. */
    suspend fun transcribe(samples: FloatArray): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            val r = checkNotNull(recognizer) { "The speech model isn't loaded yet" }
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

    /** Frees the model's memory. */
    suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock { releaseLocked() }
    }

    private fun releaseLocked() {
        recognizer?.release()
        recognizer = null
        loadedKey = null
    }
}
