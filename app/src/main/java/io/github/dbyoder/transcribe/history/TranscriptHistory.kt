package io.github.dbyoder.transcribe.history

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class Transcript(
    val id: Long,
    val text: String,
    val audioSeconds: Float,
    val processMillis: Long,
    val createdAt: Long,
    /** What the speech model heard, when clean-up changed it. */
    val original: String? = null,
    /** How the bubble put the text into the app ("typed", "pasted", "copied: ..."); null for the app screen. */
    val insertedVia: String? = null,
)

/**
 * Recent dictations, kept in app-private storage so nothing is lost if inserting the text
 * fails. Never leaves the phone (the app has no internet access and backups are off).
 */
object TranscriptHistory {
    private const val MAX_ITEMS = 50

    private val _items = MutableStateFlow<List<Transcript>>(emptyList())
    val items: StateFlow<List<Transcript>> = _items.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private val nextId = AtomicLong(1)
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "history.json")
        val loaded = runCatching { parse(file.readText()) }.getOrDefault(emptyList())
        nextId.set((loaded.maxOfOrNull { it.id } ?: 0) + 1)
        _items.value = loaded
    }

    fun add(
        text: String,
        audioSeconds: Float,
        processMillis: Long,
        original: String? = null,
        insertedVia: String? = null,
    ) {
        val item = Transcript(
            id = nextId.getAndIncrement(),
            text = text,
            audioSeconds = audioSeconds,
            processMillis = processMillis,
            createdAt = System.currentTimeMillis(),
            original = original?.takeIf { it != text },
            insertedVia = insertedVia,
        )
        _items.update { (listOf(item) + it).take(MAX_ITEMS) }
        persist()
    }

    fun clear() {
        _items.value = emptyList()
        persist()
    }

    private fun persist() {
        scope.launch {
            // Always write the latest list, so out-of-order writes can't leave stale data.
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(serialize(_items.value))
                tmp.renameTo(file)
            }
        }
    }

    private fun serialize(items: List<Transcript>): String {
        val array = JSONArray()
        for (t in items) {
            array.put(
                JSONObject()
                    .put("id", t.id)
                    .put("text", t.text)
                    .put("audioSeconds", t.audioSeconds.toDouble())
                    .put("processMillis", t.processMillis)
                    .put("createdAt", t.createdAt)
                    .apply {
                        if (t.original != null) put("original", t.original)
                        if (t.insertedVia != null) put("insertedVia", t.insertedVia)
                    },
            )
        }
        return array.toString()
    }

    private fun parse(json: String): List<Transcript> {
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Transcript(
                id = o.getLong("id"),
                text = o.getString("text"),
                audioSeconds = o.getDouble("audioSeconds").toFloat(),
                processMillis = o.getLong("processMillis"),
                createdAt = o.getLong("createdAt"),
                original = if (o.has("original")) o.getString("original") else null,
                insertedVia = if (o.has("insertedVia")) o.getString("insertedVia") else null,
            )
        }
    }
}
