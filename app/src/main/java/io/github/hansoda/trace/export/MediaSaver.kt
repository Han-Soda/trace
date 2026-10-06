package io.github.hansoda.trace.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.io.OutputStream

/** Saves exports to the shared Movies/Trace and Pictures/Trace folders. */
object MediaSaver {
    const val FOLDER = "Trace"

    fun saveVideo(context: Context, source: File, name: String): Uri =
        save(context, "$name.mp4", "video/mp4", Environment.DIRECTORY_MOVIES) { out -> source.inputStream().use { it.copyTo(out) } }

    fun saveImage(context: Context, name: String, write: (OutputStream) -> Unit): Uri =
        save(context, "$name.png", "image/png", Environment.DIRECTORY_PICTURES, write)

    private fun save(context: Context, fileName: String, mime: String, directory: String, write: (OutputStream) -> Unit): Uri {
        val resolver = context.contentResolver
        val video = mime.startsWith("video/")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = if (video) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "$directory/$FOLDER")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("Couldn't create $fileName")
            try {
                resolver.openOutputStream(uri)?.use(write) ?: throw IOException("Couldn't write $fileName")
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                return uri
            } catch (error: Exception) {
                resolver.delete(uri, null, null)
                throw error
            }
        }
        // Android 8 and 9: write the file, then register it so galleries and sharing see it.
        @Suppress("DEPRECATION")
        val folder = File(Environment.getExternalStoragePublicDirectory(directory), FOLDER)
        if (!folder.exists() && !folder.mkdirs()) throw IOException("Couldn't create ${folder.path}")
        var file = File(folder, fileName)
        var copy = 1
        while (file.exists()) file = File(folder, fileName.substringBeforeLast('.') + " (${copy++})." + fileName.substringAfterLast('.'))
        file.outputStream().use(write)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            @Suppress("DEPRECATION")
            put(MediaStore.MediaColumns.DATA, file.absolutePath)
        }
        val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        return resolver.insert(collection, values) ?: throw IOException("Couldn't register ${file.name}")
    }
}
