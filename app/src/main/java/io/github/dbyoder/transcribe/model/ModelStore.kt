package io.github.dbyoder.transcribe.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import io.github.dbyoder.transcribe.core.ModelInstallException
import io.github.dbyoder.transcribe.core.ModelInstaller
import io.github.dbyoder.transcribe.core.TransducerModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** Where the speech model lives on the phone, and how it gets there. */
class ModelStore(private val context: Context) {
    private val modelDir = File(context.filesDir, "models/speech")

    /** The installed model, or null if the user hasn't imported one yet. */
    suspend fun installed(): TransducerModel? = withContext(Dispatchers.IO) {
        ModelInstaller.load(modelDir)
    }

    /**
     * Unpacks the model archive the user picked (it's in their Downloads folder, fetched
     * by their browser, since this app has no internet access).
     *
     * @param onProgress fraction done (0..1), or null if the file size is unknown.
     */
    suspend fun import(uri: Uri, onProgress: (Float?) -> Unit): TransducerModel = withContext(Dispatchers.IO) {
        val freeBytes = context.filesDir.usableSpace
        if (freeBytes < REQUIRED_FREE_BYTES) {
            throw ModelInstallException(
                "Not enough storage: the model needs about 1 GB free while it unpacks " +
                    "(${freeBytes / 1_000_000} MB available).",
            )
        }
        val totalBytes = sizeOf(uri)
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ModelInstallException("Couldn't open that file")
        input.use {
            ModelInstaller.install(it, modelDir) { readBytes ->
                ensureActive() // lets the user's cancel (leaving the screen) stop the unpacking
                onProgress(if (totalBytes > 0) (readBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else null)
            }
        }
    }

    private fun sizeOf(uri: Uri): Long =
        context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else -1L
        } ?: -1L

    companion object {
        /** NVIDIA Parakeet TDT 0.6B v2 (English), int8, packaged for sherpa-onnx. */
        const val RECOMMENDED_MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
                "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2"

        private const val REQUIRED_FREE_BYTES = 1_000_000_000L
    }
}
