package io.github.dbyoder.transcribe.core

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelInstallerTest {
    private val root: File = Files.createTempDirectory("model-installer-test").toFile()
    private val dest = File(root, "models/speech")

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private val parakeetFiles = mapOf(
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/encoder.int8.onnx" to "ENC",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/decoder.int8.onnx" to "DEC",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/joiner.int8.onnx" to "JOIN",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/tokens.txt" to "a 0\nb 1\n",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/test_wavs/0.wav" to "RIFF....",
        "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/README.md" to "hello",
    )

    @Test
    fun installsParakeetFromTarBz2() {
        val model = ModelInstaller.install(archive(parakeetFiles, Compression.BZIP2), dest)

        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", model.name)
        assertEquals("nemo_transducer", model.modelType)
        assertEquals("ENC", model.encoder.readText())
        assertEquals("DEC", model.decoder.readText())
        assertEquals("JOIN", model.joiner.readText())
        assertEquals("a 0\nb 1\n", model.tokens.readText())
        // Sample audio and docs are skipped, and there's no nested folder.
        assertFalse(File(dest, "0.wav").exists())
        assertFalse(File(dest, "README.md").exists())
        assertFalse(File(dest, "test_wavs").exists())
        // No leftovers next to the model folder.
        assertEquals(listOf("speech"), dest.parentFile.list()!!.toList())
    }

    @Test
    fun installsFromGzipAndPlainTar() {
        val fromGzip = ModelInstaller.install(archive(parakeetFiles, Compression.GZIP), dest)
        assertEquals("ENC", fromGzip.encoder.readText())

        val fromTar = ModelInstaller.install(archive(parakeetFiles, Compression.NONE), dest)
        assertEquals("ENC", fromTar.encoder.readText())
    }

    @Test
    fun loadReturnsInstalledModel() {
        assertNull(ModelInstaller.load(dest))
        ModelInstaller.install(archive(parakeetFiles, Compression.BZIP2), dest)

        val loaded = assertNotNull(ModelInstaller.load(dest))
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8", loaded.name)
        assertEquals(File(dest, "encoder.int8.onnx").absolutePath, loaded.encoder.absolutePath)
    }

    @Test
    fun loadReturnsNullWhenAFileIsMissing() {
        ModelInstaller.install(archive(parakeetFiles, Compression.BZIP2), dest)
        File(dest, "joiner.int8.onnx").delete()
        assertNull(ModelInstaller.load(dest))
    }

    @Test
    fun failedInstallKeepsPreviousModel() {
        ModelInstaller.install(archive(parakeetFiles, Compression.BZIP2), dest)

        val noJoiner = parakeetFiles.filterKeys { !it.contains("joiner") }
        val error = assertFailsWith<ModelInstallException> {
            ModelInstaller.install(archive(noJoiner, Compression.BZIP2), dest)
        }
        assertTrue(error.message!!.contains("joiner"), error.message)

        val stillThere = assertNotNull(ModelInstaller.load(dest))
        assertEquals("JOIN", stillThere.joiner.readText())
        assertEquals(listOf("speech"), dest.parentFile.list()!!.toList())
    }

    @Test
    fun reinstallReplacesPreviousModel() {
        ModelInstaller.install(archive(parakeetFiles, Compression.BZIP2), dest)
        val v3 = mapOf(
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/encoder.int8.onnx" to "ENC3",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/decoder.int8.onnx" to "DEC3",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/joiner.int8.onnx" to "JOIN3",
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8/tokens.txt" to "c 0\n",
        )
        val model = ModelInstaller.install(archive(v3, Compression.BZIP2), dest)
        assertEquals("sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8", model.name)
        assertEquals("ENC3", ModelInstaller.load(dest)!!.encoder.readText())
    }

    @Test
    fun prefersInt8WhenArchiveHasBothPrecisions() {
        val both = parakeetFiles + mapOf(
            "sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/encoder.onnx" to "BIG-ENC",
        )
        val model = ModelInstaller.install(archive(both, Compression.BZIP2), dest)
        assertEquals("encoder.int8.onnx", model.encoder.name)
        assertFalse(File(dest, "encoder.onnx").exists(), "unused full-precision encoder should be deleted")
    }

    @Test
    fun pathsInsideArchiveCannotEscapeModelFolder() {
        val evil = parakeetFiles + mapOf("../../evil.onnx" to "x", "/etc/also-evil.onnx" to "y")
        ModelInstaller.install(archive(evil, Compression.BZIP2), dest)

        assertFalse(File(root, "evil.onnx").exists())
        assertFalse(File(dest.parentFile, "evil.onnx").exists())
        // They were treated as plain file names inside the staging folder, then removed as unused.
        assertFalse(File(dest, "evil.onnx").exists())
    }

    @Test
    fun rejectsNonArchiveFile() {
        val error = assertFailsWith<ModelInstallException> {
            ModelInstaller.install(ByteArrayInputStream("just some text, not a tarball".toByteArray()), dest)
        }
        assertNotNull(error.message)
        assertNull(ModelInstaller.load(dest))
        assertFalse(dest.exists())
    }

    @Test
    fun nonNemoModelLetsSherpaDetectType() {
        val zipformer = mapOf(
            "sherpa-onnx-zipformer-en-2023-06-26/encoder-epoch-99-avg-1.int8.onnx" to "E",
            "sherpa-onnx-zipformer-en-2023-06-26/decoder-epoch-99-avg-1.onnx" to "D",
            "sherpa-onnx-zipformer-en-2023-06-26/joiner-epoch-99-avg-1.int8.onnx" to "J",
            "sherpa-onnx-zipformer-en-2023-06-26/tokens.txt" to "t",
        )
        val model = ModelInstaller.install(archive(zipformer, Compression.BZIP2), dest)
        assertEquals("", model.modelType)
    }

    @Test
    fun reportsProgressAndCanBeCancelled() {
        // ~3 MB of incompressible-ish content so progress fires more than once.
        val random = java.util.Random(42)
        val bigEncoder = String(CharArray(3 shl 20) { ('a'.code + random.nextInt(26)).toChar() })
        val files = parakeetFiles + ("sherpa-onnx-nemo-parakeet-tdt-0.6b-v2-int8/encoder.int8.onnx" to bigEncoder)

        val seen = mutableListOf<Long>()
        ModelInstaller.install(archive(files, Compression.NONE), dest) { seen += it }
        assertTrue(seen.size >= 2, "expected progress updates, got $seen")
        assertEquals(seen.sorted(), seen)

        class Stop : RuntimeException()
        assertFailsWith<Stop> {
            ModelInstaller.install(archive(files, Compression.NONE), File(root, "other")) { throw Stop() }
        }
        assertFalse(File(root, "other").exists())
        assertFalse(File(root, "other.staging").exists())
    }

    private enum class Compression { NONE, GZIP, BZIP2 }

    private fun archive(files: Map<String, String>, compression: Compression): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        val compressed: OutputStream = when (compression) {
            Compression.NONE -> bytes
            Compression.GZIP -> GZIPOutputStream(bytes)
            Compression.BZIP2 -> BZip2CompressorOutputStream(bytes)
        }
        TarArchiveOutputStream(compressed).use { tar ->
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
            for ((name, content) in files) {
                val data = content.toByteArray()
                val entry = TarArchiveEntry(name)
                entry.size = data.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(data)
                tar.closeArchiveEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }
}
