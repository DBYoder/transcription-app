package io.github.dbyoder.transcribe.core

import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.util.Properties
import java.util.zip.GZIPInputStream

/** The files sherpa-onnx needs to run a transducer speech model such as NVIDIA Parakeet. */
data class TransducerModel(
    val name: String,
    val encoder: File,
    val decoder: File,
    val joiner: File,
    val tokens: File,
) {
    /**
     * The sherpa-onnx model type. Parakeet is a NeMo model; for anything else we leave it
     * empty so sherpa-onnx reads the type from the model's own metadata.
     */
    val modelType: String
        get() = if (name.contains("parakeet", ignoreCase = true) || name.contains("nemo", ignoreCase = true)) {
            "nemo_transducer"
        } else {
            ""
        }
}

class ModelInstallException(message: String) : IOException(message)

/**
 * Installs a speech model from a sherpa-onnx model archive, e.g.
 * `sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8.tar.bz2`.
 *
 * The app has no internet access, so the user downloads the archive in their browser and
 * picks it in the app. This unpacks it into app storage.
 */
object ModelInstaller {
    private const val INFO_FILE = "model.properties"
    private const val PROGRESS_STEP_BYTES = 1L shl 20 // report progress every 1 MB
    private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")

    /**
     * Unpacks [archive] (.tar.bz2, .tar.gz or plain .tar) into [destDir].
     *
     * Only the model files (`*.onnx` and `tokens.txt`) are kept; sample audio, READMEs and
     * anything else are skipped. Directory structure inside the archive is ignored, so a
     * malicious archive can't write outside [destDir].
     *
     * The install is all-or-nothing: an existing model in [destDir] is only replaced once
     * the new one has been fully unpacked and checked.
     *
     * @param onProgress called with the number of archive bytes read so far. It may throw
     *   (e.g. a coroutine cancellation) to abort the install.
     */
    fun install(archive: InputStream, destDir: File, onProgress: (Long) -> Unit = {}): TransducerModel {
        val parent = destDir.absoluteFile.parentFile ?: throw ModelInstallException("Invalid model folder")
        val staging = File(parent, destDir.name + ".staging")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw ModelInstallException("Couldn't create ${staging.path}")

        try {
            val counted = CountingInputStream(archive, onProgress)
            val modelName = unpack(decompress(BufferedInputStream(counted, 1 shl 16)), staging)
            val model = pickModelFiles(staging, modelName)
            writeInfo(staging, model)
            swapInto(staging, destDir)
            return load(destDir) ?: throw ModelInstallException("Model files went missing after install")
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** Returns the installed model in [dir], or null if there isn't a complete one. */
    fun load(dir: File): TransducerModel? {
        val info = File(dir, INFO_FILE)
        if (!info.isFile) return null
        val props = Properties().apply { info.inputStream().use { load(it) } }
        fun file(key: String): File? = props.getProperty(key)?.let { File(dir, it) }?.takeIf { it.isFile }
        return TransducerModel(
            name = props.getProperty("name") ?: "Unknown model",
            encoder = file("encoder") ?: return null,
            decoder = file("decoder") ?: return null,
            joiner = file("joiner") ?: return null,
            tokens = file("tokens") ?: return null,
        )
    }

    private fun decompress(input: BufferedInputStream): InputStream {
        input.mark(4)
        val magic = ByteArray(3)
        var n = 0
        while (n < magic.size) {
            val read = input.read(magic, n, magic.size - n)
            if (read < 0) break
            n += read
        }
        input.reset()
        return when {
            n == 3 && magic[0] == 'B'.code.toByte() && magic[1] == 'Z'.code.toByte() && magic[2] == 'h'.code.toByte() ->
                BZip2CompressorInputStream(input, true)
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                GZIPInputStream(input, 1 shl 16)
            else -> input
        }
    }

    /** Extracts the model files into [dir] and returns the archive's top-level folder name. */
    private fun unpack(input: InputStream, dir: File): String? {
        var topLevelName: String? = null
        var sawAnyEntry = false
        try {
            TarArchiveInputStream(input).use { tar ->
                while (true) {
                    val entry = tar.nextEntry ?: break
                    sawAnyEntry = true
                    val path = entry.name.trimStart('/').removePrefix("./")
                    if (topLevelName == null && path.contains('/')) topLevelName = path.substringBefore('/')
                    if (!entry.isFile) continue

                    val fileName = path.substringAfterLast('/')
                    val wanted = fileName.endsWith(".onnx") || fileName == "tokens.txt"
                    if (!wanted || fileName.startsWith('.') || !SAFE_NAME.matches(fileName)) continue

                    val out = File(dir, fileName)
                    if (out.exists()) {
                        throw ModelInstallException("This file contains more than one model ($fileName appears twice)")
                    }
                    out.outputStream().use { tar.copyTo(it, 1 shl 16) }
                }
            }
        } catch (e: ModelInstallException) {
            throw e
        } catch (e: IOException) {
            throw ModelInstallException(
                "Couldn't read that file. Pick the .tar.bz2 model file you downloaded (${e.message})",
            )
        }
        if (!sawAnyEntry) throw ModelInstallException("That file isn't a model archive (.tar.bz2)")
        return topLevelName
    }

    private fun pickModelFiles(dir: File, archiveName: String?): TransducerModel {
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val onnx = files.filter { it.name.endsWith(".onnx") }

        fun pick(role: String): File {
            val candidates = onnx.filter { it.name.contains(role, ignoreCase = true) }
            return when {
                candidates.size == 1 -> candidates.single()
                candidates.size > 1 -> candidates.singleOrNull { it.name.contains(".int8.") }
                    ?: throw ModelInstallException("This file has several $role models; not sure which to use")
                else -> throw ModelInstallException(
                    "This file doesn't contain a supported speech model (no $role). " +
                        "Use a sherpa-onnx transducer model such as Parakeet TDT.",
                )
            }
        }

        val tokens = files.firstOrNull { it.name == "tokens.txt" }
            ?: throw ModelInstallException("This file doesn't contain a supported speech model (no tokens.txt)")
        val model = TransducerModel(
            name = archiveName ?: "Speech model",
            encoder = pick("encoder"),
            decoder = pick("decoder"),
            joiner = pick("joiner"),
            tokens = tokens,
        )

        // Drop any model files we won't use (e.g. full-precision copies) to save space.
        val used = setOf(model.encoder, model.decoder, model.joiner, model.tokens)
        files.filter { it !in used }.forEach { it.delete() }
        return model
    }

    private fun writeInfo(dir: File, model: TransducerModel) {
        val props = Properties().apply {
            setProperty("name", model.name)
            setProperty("encoder", model.encoder.name)
            setProperty("decoder", model.decoder.name)
            setProperty("joiner", model.joiner.name)
            setProperty("tokens", model.tokens.name)
        }
        File(dir, INFO_FILE).outputStream().use { props.store(it, "Installed speech model") }
    }

    private fun swapInto(staging: File, destDir: File) {
        val old = File(staging.parentFile, destDir.name + ".old")
        old.deleteRecursively()
        if (destDir.exists() && !destDir.renameTo(old)) {
            throw ModelInstallException("Couldn't replace the existing model")
        }
        if (!staging.renameTo(destDir)) {
            old.renameTo(destDir) // put the previous model back
            throw ModelInstallException("Couldn't move the new model into place")
        }
        old.deleteRecursively()
    }

    private class CountingInputStream(
        input: InputStream,
        private val onProgress: (Long) -> Unit,
    ) : FilterInputStream(input) {
        private var count = 0L
        private var lastReported = 0L

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) advance(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) advance(n.toLong())
            return n
        }

        override fun skip(n: Long): Long {
            val skipped = super.skip(n)
            if (skipped > 0) advance(skipped)
            return skipped
        }

        // The decompressor is wrapped around a BufferedInputStream, so mark/reset never
        // reach this stream and can't skew the count.
        override fun markSupported(): Boolean = false

        private fun advance(n: Long) {
            count += n
            if (count - lastReported >= PROGRESS_STEP_BYTES) {
                lastReported = count
                onProgress(count)
            }
        }
    }
}
