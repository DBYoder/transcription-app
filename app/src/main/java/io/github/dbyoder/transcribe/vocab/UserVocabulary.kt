package io.github.dbyoder.transcribe.vocab

import android.content.Context
import io.github.dbyoder.transcribe.core.DictionaryEntry
import io.github.dbyoder.transcribe.core.Snippet
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

/**
 * Your own words (spelled your way) and snippets (text you insert by voice), kept in
 * app-private storage. Shared by the bubble and the app screen.
 */
object UserVocabulary {
    private val _words = MutableStateFlow<List<DictionaryEntry>>(emptyList())
    val words: StateFlow<List<DictionaryEntry>> = _words.asStateFlow()

    private val _snippets = MutableStateFlow<List<Snippet>>(emptyList())
    val snippets: StateFlow<List<Snippet>> = _snippets.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeLock = Mutex()
    private lateinit var file: File

    fun init(context: Context) {
        file = File(context.filesDir, "vocabulary.json")
        runCatching { JSONObject(file.readText()) }.getOrNull()?.let { json ->
            _words.value = runCatching { parseWords(json.optJSONArray("words")) }.getOrDefault(emptyList())
            _snippets.value = runCatching { parseSnippets(json.optJSONArray("snippets")) }.getOrDefault(emptyList())
        }
    }

    /**
     * Adds [word], fixing whatever's in [heardAs]. If the word is already there, the new
     * spellings are added to it.
     */
    fun addWord(word: String, heardAs: List<String>) {
        val w = word.trim()
        if (w.isEmpty()) return
        val heard = heardAs.map { it.trim() }.filter { it.isNotEmpty() && it != w }
        _words.update { list ->
            val i = list.indexOfFirst { it.word == w }
            if (i < 0) {
                list + DictionaryEntry(w, heard.distinctBy { it.lowercase() })
            } else {
                list.toMutableList().also {
                    it[i] = DictionaryEntry(w, (list[i].heardAs + heard).distinctBy { h -> h.lowercase() })
                }
            }
        }
        persist()
    }

    fun removeWord(entry: DictionaryEntry) {
        _words.update { it - entry }
        persist()
    }

    /** Adds a snippet, replacing any with the same trigger. */
    fun addSnippet(trigger: String, text: String) {
        val t = trigger.trim()
        if (t.isEmpty() || text.isBlank()) return
        _snippets.update { list -> list.filterNot { it.trigger.equals(t, ignoreCase = true) } + Snippet(t, text.trim()) }
        persist()
    }

    fun removeSnippet(snippet: Snippet) {
        _snippets.update { it - snippet }
        persist()
    }

    private fun persist() {
        scope.launch {
            // Always write the latest lists, so out-of-order writes can't leave stale data.
            writeLock.withLock {
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(serialize())
                tmp.renameTo(file)
            }
        }
    }

    private fun serialize(): String {
        val words = JSONArray()
        for (e in _words.value) {
            words.put(JSONObject().put("word", e.word).put("heardAs", JSONArray(e.heardAs)))
        }
        val snippets = JSONArray()
        for (s in _snippets.value) {
            snippets.put(JSONObject().put("trigger", s.trigger).put("text", s.text))
        }
        return JSONObject().put("words", words).put("snippets", snippets).toString()
    }

    private fun parseWords(array: JSONArray?): List<DictionaryEntry> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            val heard = o.optJSONArray("heardAs")
            DictionaryEntry(
                word = o.getString("word"),
                heardAs = if (heard == null) emptyList() else (0 until heard.length()).map { heard.getString(it) },
            )
        }
    }

    private fun parseSnippets(array: JSONArray?): List<Snippet> {
        if (array == null) return emptyList()
        return (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Snippet(trigger = o.getString("trigger"), text = o.getString("text"))
        }
    }
}
