package io.github.dbyoder.transcribe.speech

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import io.github.dbyoder.transcribe.AppSettings
import io.github.dbyoder.transcribe.core.BpeVocab
import io.github.dbyoder.transcribe.core.PersonalDictionary
import io.github.dbyoder.transcribe.core.SAMPLE_RATE
import io.github.dbyoder.transcribe.core.TransducerModel
import io.github.dbyoder.transcribe.model.ModelStore
import io.github.dbyoder.transcribe.vocab.UserVocabulary
import java.io.File
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
 *
 * With words in your dictionary, it decodes with a small beam search that listens out for them
 * ("hotwords"), which helps it catch names it would otherwise mishear. That's a little slower
 * than the plain decoding used otherwise, and can be switched off in the app.
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

    /** The dictionary words being listened for, in sherpa-onnx's format; empty when not boosting. */
    private var hotwords = ""

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
            val stream = if (hotwords.isNotEmpty()) r.createStream(hotwords) else r.createStream()
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
        val wanted = if (AppSettings.boostWords(appContext)) PersonalDictionary.hotwords(UserVocabulary.words.value) else ""
        val vocab = if (wanted.isNotEmpty()) bpeVocabFor(model) else null
        val boost = vocab != null
        // lastModified changes when a model is re-imported, which forces a reload. Changing the
        // words doesn't: they're passed with each recording.
        val key = "${model.encoder.absolutePath}|${model.encoder.lastModified()}|$threads|$boost"
        if (recognizer == null || key != loadedKey) {
            releaseLocked()
            val millis = measureTimeMillis { recognizer = OfflineRecognizer(config = config(model, threads, vocab)) }
            loadedKey = key
            loadInfo = LoadInfo(model.name, threads, millis)
        }
        hotwords = if (boost) wanted else ""
        scheduleIdleUnload()
        return loadInfo
    }

    private fun config(model: TransducerModel, threads: Int, bpeVocab: File?) = OfflineRecognizerConfig(
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
            modelingUnit = if (bpeVocab != null) "bpe" else "",
            bpeVocab = bpeVocab?.absolutePath ?: "",
        ),
        decodingMethod = if (bpeVocab != null) "modified_beam_search" else "greedy_search",
        maxActivePaths = 4,
    )

    /**
     * The word-piece list sherpa-onnx needs to listen for dictionary words: the model's own
     * bpe.vocab if it came with one, otherwise one rebuilt from its tokens.txt. Null if the model
     * doesn't use word pieces, in which case words aren't boosted.
     */
    private fun bpeVocabFor(model: TransducerModel): File? {
        val dir = model.tokens.parentFile
        if (dir != null) {
            val shipped = File(dir, "bpe.vocab")
            if (shipped.isFile && shipped.length() > 0) return shipped
        }
        val made = File(appContext.filesDir, "speech-bpe.vocab")
        val stamp = File(appContext.filesDir, "speech-bpe.vocab.source")
        val source = "${model.tokens.absolutePath}|${model.tokens.lastModified()}"
        if (made.isFile && stamp.isFile && runCatching { stamp.readText() }.getOrNull() == source) return made
        val text = runCatching { BpeVocab.fromTokens(model.tokens.readText()) }.getOrNull() ?: return null
        return runCatching {
            made.writeText(text)
            stamp.writeText(source)
            made
        }.getOrNull()
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
        hotwords = ""
        loadedKey = null
        loadInfo = null
    }
}
