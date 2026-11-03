package io.github.dbyoder.transcribe.journal

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/** Where the journal's Markdown files live. All calls do file I/O: run them off the main thread. */
interface JournalFolder {
    /** Shown in the app, e.g. "Documents/Journal" or "Inside this app". */
    val description: String

    /** Every file name in the folder (journal files are picked out by name). */
    fun list(): List<String>

    fun read(name: String): String?

    fun write(name: String, text: String)

    fun delete(name: String)
}

/** Files in the app's own storage: private to this app. */
class PrivateJournalFolder(context: Context) : JournalFolder {
    private val dir = File(context.filesDir, "journal").apply { mkdirs() }

    override val description = "Inside this app"

    override fun list(): List<String> = dir.list()?.toList().orEmpty()

    override fun read(name: String): String? = File(dir, name).takeIf { it.isFile }?.readText()

    override fun write(name: String, text: String) {
        val tmp = File(dir, "$name.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(File(dir, name))) throw IllegalStateException("Couldn't save $name")
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/**
 * Files in a folder you picked (Android's Storage Access Framework), so other apps such as
 * Obsidian or a sync app can use them too. The app keeps access through a persisted permission.
 */
class DocumentJournalFolder(private val context: Context, private val tree: Uri) : JournalFolder {
    private val resolver get() = context.contentResolver
    private val treeId = DocumentsContract.getTreeDocumentId(tree)
    private val folderUri = DocumentsContract.buildDocumentUriUsingTree(tree, treeId)

    /** File name → document id, from the last listing. */
    private val ids = HashMap<String, String>()

    /** "primary:Documents/Journal" → "Documents/Journal" */
    override val description: String = treeId.substringAfter(':').ifEmpty { "Your journal folder" }

    override fun list(): List<String> {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val names = ArrayList<String>()
        synchronized(ids) {
            ids.clear()
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getString(0) ?: continue
                    val name = cursor.getString(1) ?: continue
                    ids[name] = id
                    names += name
                }
            }
        }
        return names
    }

    private fun uriOf(name: String): Uri? {
        val id = synchronized(ids) { ids[name] } ?: run {
            list()
            synchronized(ids) { ids[name] }
        } ?: return null
        return DocumentsContract.buildDocumentUriUsingTree(tree, id)
    }

    override fun read(name: String): String? {
        val uri = uriOf(name) ?: return null
        return resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun write(name: String, text: String) {
        val uri = uriOf(name)
            // "application/octet-stream" so the folder keeps the ".md" name exactly as given.
            ?: DocumentsContract.createDocument(resolver, folderUri, "application/octet-stream", name)
            ?: throw IllegalStateException("Couldn't create $name in your journal folder")
        resolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: throw IllegalStateException("Couldn't write $name in your journal folder")
        DocumentsContract.getDocumentId(uri)?.let { id -> synchronized(ids) { ids[name] = id } }
    }

    override fun delete(name: String) {
        val uri = uriOf(name) ?: return
        DocumentsContract.deleteDocument(resolver, uri)
        synchronized(ids) { ids.remove(name) }
    }
}
