package io.github.dbyoder.transcribe.ai

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.dbyoder.transcribe.core.ModelInstallException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The optional AI model behind the formatting buttons: a single `.litertlm` file the user
 * downloads in their browser (the app has no internet access) and imports here.
 */
class FormatModelStore(private val context: Context) {
    private val dir = File(context.filesDir, "models/format")
    private val modelFile = File(dir, "model.litertlm")
    private val nameFile = File(dir, "name.txt")

    /** The installed model file, or null if there isn't one. */
    fun installed(): File? = modelFile.takeIf { it.isFile && it.length() > 0 }

    /** The file name the model was imported from, e.g. "gemma-4-E2B-it.litertlm". */
    fun installedName(): String? = if (installed() != null) nameFile.takeIf { it.isFile }?.readText()?.trim() else null

    /** Copies the picked `.litertlm` file into app storage. [onProgress] gets 0..1, or null if unknown. */
    suspend fun import(uri: Uri, onProgress: (Float?) -> Unit) = withContext(Dispatchers.IO) {
        val (name, size) = describe(uri)
        if (name != null && !name.endsWith(".litertlm", ignoreCase = true)) {
            throw ModelInstallException("Pick the file ending in .litertlm (this one is \"$name\").")
        }
        val needed = (if (size > 0) size else 3_000_000_000L) + 200_000_000L
        if (context.filesDir.usableSpace < needed) {
            throw ModelInstallException(
                "Not enough storage: the AI model needs about ${"%.1f".format(needed / 1e9)} GB free " +
                    "(${context.filesDir.usableSpace / 1_000_000} MB available).",
            )
        }

        dir.mkdirs()
        val partial = File(dir, "model.litertlm.part")
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw ModelInstallException("Couldn't open that file")
            input.use { source ->
                partial.outputStream().use { out ->
                    val buffer = ByteArray(1 shl 20)
                    var copied = 0L
                    var lastReported = -1
                    while (true) {
                        ensureActive()
                        val n = source.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        copied += n
                        val percent = if (size > 0) (copied * 100 / size).toInt() else -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(if (size > 0) (copied.toFloat() / size).coerceIn(0f, 1f) else null)
                        }
                    }
                }
            }
            if (partial.length() < 50_000_000L) {
                throw ModelInstallException("That file is too small to be an AI model.")
            }
            modelFile.delete()
            if (!partial.renameTo(modelFile)) throw ModelInstallException("Couldn't save the AI model")
            nameFile.writeText(name ?: "AI model")
        } finally {
            partial.delete()
        }
    }

    /** Deletes the model to free up storage. */
    fun remove() {
        dir.deleteRecursively()
    }

    private fun describe(uri: Uri): Pair<String?, Long> =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { c ->
                if (!c.moveToFirst()) return@use null to -1L
                val name = if (c.isNull(0)) null else c.getString(0)
                val size = if (c.isNull(1)) -1L else c.getLong(1)
                name to size
            } ?: (null to -1L)

    companion object {
        /** Google's Gemma 4 E2B, packaged for LiteRT-LM (about 2.6 GB, Apache 2.0 licence). */
        const val RECOMMENDED_MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm"

        /** The model's page, in case the direct link changes. */
        const val RECOMMENDED_MODEL_PAGE = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm"
    }
}
