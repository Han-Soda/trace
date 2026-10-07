package io.github.hansoda.trace.data

import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import io.github.hansoda.trace.R
import java.io.File
import java.io.FileNotFoundException

/**
 * Lists Trace among the places a file can be saved, so Google's "Export Timeline data" can save
 * straight into it. What's saved waits in the [Inbox] until Trace next comes to the front.
 */
class TimelineInbox : DocumentsProvider() {
    private val app: Context get() = context ?: throw IllegalStateException("Not attached")

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor = MatrixCursor(projection ?: ROOT_COLUMNS).apply {
        newRow()
            .add(Root.COLUMN_ROOT_ID, ROOT)
            .add(Root.COLUMN_DOCUMENT_ID, FOLDER)
            .add(Root.COLUMN_TITLE, app.getString(R.string.app_name))
            .add(Root.COLUMN_SUMMARY, app.getString(R.string.inbox_summary))
            .add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE or Root.FLAG_LOCAL_ONLY)
            .add(Root.COLUMN_ICON, R.mipmap.ic_launcher)
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply { addDocument(this, documentId) }

    override fun queryChildDocuments(parentDocumentId: String, projection: Array<out String>?, sortOrder: String?): Cursor =
        MatrixCursor(projection ?: DOCUMENT_COLUMNS).apply {
            if (parentDocumentId == FOLDER) for (file in Inbox.files(app)) addDocument(this, file.name)
        }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String {
        if (parentDocumentId != FOLDER || mimeType == Document.MIME_TYPE_DIR) throw UnsupportedOperationException("Files only")
        return Inbox.create(app, displayName).name
    }

    override fun openDocument(documentId: String, mode: String, signal: CancellationSignal?): ParcelFileDescriptor {
        val file = Inbox.file(app, documentId) ?: throw FileNotFoundException(documentId)
        val access = ParcelFileDescriptor.parseMode(mode)
        if ('w' !in mode) return ParcelFileDescriptor.open(file, access)
        // The file is ready to import once whoever saves it has finished and closed it.
        return ParcelFileDescriptor.open(file, access, Handler(Looper.getMainLooper())) { error ->
            if (error == null) Inbox.finished(app, documentId)
        }
    }

    override fun deleteDocument(documentId: String) {
        Inbox.file(app, documentId)?.let { Inbox.remove(listOf(it)) }
    }

    private fun addDocument(cursor: MatrixCursor, documentId: String) {
        if (documentId == FOLDER) {
            cursor.newRow()
                .add(Document.COLUMN_DOCUMENT_ID, FOLDER)
                .add(Document.COLUMN_DISPLAY_NAME, app.getString(R.string.app_name))
                .add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
                .add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
                .add(Document.COLUMN_SIZE, null)
                .add(Document.COLUMN_LAST_MODIFIED, null)
            return
        }
        val file = Inbox.file(app, documentId) ?: throw FileNotFoundException(documentId)
        cursor.newRow()
            .add(Document.COLUMN_DOCUMENT_ID, documentId)
            .add(Document.COLUMN_DISPLAY_NAME, file.name)
            .add(Document.COLUMN_MIME_TYPE, Inbox.mimeType(file.name))
            .add(Document.COLUMN_FLAGS, Document.FLAG_SUPPORTS_WRITE or Document.FLAG_SUPPORTS_DELETE)
            .add(Document.COLUMN_SIZE, file.length())
            .add(Document.COLUMN_LAST_MODIFIED, file.lastModified())
    }

    private companion object {
        const val ROOT = "trace"
        const val FOLDER = "inbox"
        val ROOT_COLUMNS = arrayOf(
            Root.COLUMN_ROOT_ID, Root.COLUMN_DOCUMENT_ID, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY, Root.COLUMN_FLAGS, Root.COLUMN_ICON,
        )
        val DOCUMENT_COLUMNS = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED,
        )
    }
}

/** Files saved into Trace by other apps, waiting to be imported. */
object Inbox {
    private const val FINISHED = ".finished"

    private fun folder(context: Context): File = File(context.filesDir, "inbox").apply { mkdirs() }

    /** Everything saved here, oldest first. */
    fun files(context: Context): List<File> =
        folder(context).listFiles()?.filter { it.isFile && !it.name.endsWith(FINISHED) }?.sortedBy { it.lastModified() }.orEmpty()

    /** A saved file by name, or null for anything that isn't one. */
    fun file(context: Context, name: String): File? {
        if ('/' in name || name.startsWith(".") || name.endsWith(FINISHED)) return null
        return File(folder(context), name).takeIf { it.isFile }
    }

    /** A new, empty file to save into, named after [displayName]. */
    fun create(context: Context, displayName: String): File {
        val folder = folder(context)
        val clean = displayName.replace(Regex("[/\\\\:*?\"<>|\\u0000-\\u001f]"), "_").trimStart('.').ifBlank { "Timeline.json" }
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        var name = clean
        var copy = 2
        while (File(folder, name).exists()) {
            name = clean.substring(0, dot) + " ($copy)" + clean.substring(dot)
            copy++
        }
        val file = File(folder, name)
        if (!file.createNewFile()) throw FileNotFoundException(name)
        return file
    }

    /** Marks [name] as completely saved. */
    fun finished(context: Context, name: String) {
        File(folder(context), name + FINISHED).createNewFile()
    }

    /** Completely saved files, newest first. */
    fun waiting(context: Context): List<File> =
        files(context).filter { it.length() > 0 && File(it.path + FINISHED).exists() }.reversed()

    fun remove(files: List<File>) {
        for (file in files) {
            File(file.path + FINISHED).delete()
            file.delete()
        }
    }

    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "json" -> "application/json"
        "zip" -> "application/zip"
        else -> "application/octet-stream"
    }
}
