package io.github.dbyoder.transcribe.ai

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import io.github.dbyoder.transcribe.core.FormatOutput
import io.github.dbyoder.transcribe.core.FormatStyle
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
 * Rewrites dictated text with a small AI model running on the phone (LiteRT-LM), for the
 * Polish / Bullets / Email / Shorter / Prompt buttons.
 *
 * The model is big (a couple of GB in memory), so it loads on first use (GPU if the phone
 * allows, otherwise CPU) and unloads after [IDLE_UNLOAD_MS] without use.
 */
object Formatter {
    private const val IDLE_UNLOAD_MS = 5 * 60 * 1000L
    private const val MAX_OUTPUT_TOKENS = 1024

    data class LoadInfo(val backend: String, val loadMillis: Long)

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var appContext: Context
    private var engine: Engine? = null
    private var loadedKey: String? = null
    private var loadInfo: LoadInfo? = null
    private var idleJob: Job? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun isInstalled(): Boolean = FormatModelStore(appContext).installed() != null

    /** Loads the model if needed. Returns null when no model is installed. */
    suspend fun ensureLoaded(): LoadInfo? = withContext(Dispatchers.Default) {
        mutex.withLock { loadLocked() }
    }

    /** Rewrites [text] in the given [style]. Takes a few seconds. */
    suspend fun format(text: String, style: FormatStyle): String = withContext(Dispatchers.Default) {
        mutex.withLock {
            loadLocked() ?: throw IllegalStateException("The AI model isn't set up yet. Open Transcribe to add it.")
            val config = ConversationConfig(
                systemInstruction = Contents.of(FormatStyle.SYSTEM_INSTRUCTION),
                // Near-greedy: we want the faithful edit, not creativity.
                samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.1),
                maxOutputToken = MAX_OUTPUT_TOKENS,
                thinkingConfig = ThinkingConfig(enableThinking = false),
            )
            val reply = checkNotNull(engine).createConversation(config).use { conversation ->
                conversation.sendMessage(FormatStyle.prompt(style, text)).toString()
            }
            scheduleIdleUnload()
            FormatOutput.clean(reply, style.bullet).ifBlank { throw IllegalStateException("The AI model returned nothing") }
        }
    }

    suspend fun unload() = withContext(Dispatchers.Default) {
        mutex.withLock { releaseLocked() }
    }

    private fun loadLocked(): LoadInfo? {
        val file = FormatModelStore(appContext).installed() ?: return null
        val key = "${file.absolutePath}|${file.lastModified()}"
        if (engine == null || key != loadedKey) {
            releaseLocked()
            var loaded: Engine? = null
            var backend = "GPU"
            val millis = measureTimeMillis {
                loaded = try {
                    start(file.absolutePath, Backend.GPU())
                } catch (e: Exception) {
                    // Not every phone lets apps use the GPU this way; the CPU always works.
                    backend = "CPU"
                    start(file.absolutePath, Backend.CPU())
                }
            }
            engine = loaded
            loadedKey = key
            loadInfo = LoadInfo(backend, millis)
        }
        scheduleIdleUnload()
        return loadInfo
    }

    private fun start(modelPath: String, backend: Backend): Engine {
        val engine = Engine(EngineConfig(modelPath = modelPath, backend = backend, cacheDir = appContext.cacheDir.path))
        try {
            engine.initialize()
        } catch (e: Throwable) {
            runCatching { engine.close() }
            throw e
        }
        return engine
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_UNLOAD_MS)
            unload()
        }
    }

    private fun releaseLocked() {
        runCatching { engine?.close() }
        engine = null
        loadedKey = null
        loadInfo = null
    }
}
