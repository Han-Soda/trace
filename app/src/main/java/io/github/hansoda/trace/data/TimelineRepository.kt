package io.github.hansoda.trace.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.gson.stream.MalformedJsonException
import java.io.BufferedInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** What was imported, shown in settings. */
data class TimelineInfo(val name: String, val points: Int, val importedAt: Long)

/**
 * Imports Timeline exports and keeps the result in the app's private storage, so the original
 * file doesn't need to stay around.
 */
class TimelineRepository(private val context: Context) {
    private val file = File(context.filesDir, "timeline.bin")
    private val prefs = context.getSharedPreferences("timeline", Context.MODE_PRIVATE)

    fun info(): TimelineInfo? {
        if (!file.exists()) return null
        return TimelineInfo(
            prefs.getString(NAME, null) ?: return null,
            prefs.getInt(POINTS, 0),
            prefs.getLong(IMPORTED_AT, 0),
        )
    }

    suspend fun load(): Timeline? = withContext(Dispatchers.IO) {
        if (file.exists()) runCatching { TimelineCodec.read(file) }.getOrNull() else null
    }

    /** Reads one or more exports (JSON, or ZIP archives of them) into a single timeline. */
    suspend fun import(uris: List<Uri>, onProgress: (Float) -> Unit): Pair<Timeline, TimelineInfo> = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val sizes = uris.map { uri -> query(uri, OpenableColumns.SIZE)?.toLongOrNull() ?: -1L }
        val total = sizes.sumOf { maxOf(it, 0) }.coerceAtLeast(1)
        val names = uris.map { uri -> query(uri, OpenableColumns.DISPLAY_NAME) ?: uri.lastPathSegment ?: "Timeline" }
        val builder = TimelineBuilder()
        var before = 0L
        uris.forEachIndexed { index, uri ->
            val stream = resolver.openInputStream(uri) ?: throw java.io.FileNotFoundException(names[index])
            val counting = CountingStream(stream) { read -> onProgress(((before + read).toFloat() / total).coerceIn(0f, 1f)) }
            BufferedInputStream(counting, 1 shl 16).use { input ->
                if (isZip(input)) readZip(input, builder) else TimelineParser(builder).parse(input.reader())
            }
            before += maxOf(sizes[index], counting.count)
            coroutineContext.ensureActive()
        }
        val timeline = builder.build()
        if (timeline.isEmpty()) throw TimelineFormatException(TimelineFormatException.Reason.NO_LOCATIONS)
        TimelineCodec.write(timeline, file)
        val name = if (names.size == 1) names.first() else "${names.first()} +${names.size - 1}"
        val info = TimelineInfo(name, timeline.size, System.currentTimeMillis())
        prefs.edit().putString(NAME, info.name).putInt(POINTS, info.points).putLong(IMPORTED_AT, info.importedAt).apply()
        timeline to info
    }

    fun clear() {
        file.delete()
        prefs.edit().clear().apply()
    }

    private suspend fun readZip(input: InputStream, builder: TimelineBuilder) {
        val zip = ZipInputStream(input)
        var found = false
        var entry = zip.nextEntry
        while (entry != null) {
            if (!entry.isDirectory && entry.name.endsWith(".json", ignoreCase = true)) {
                try {
                    TimelineParser(builder).parse(KeepOpen(zip).reader())
                    found = true
                } catch (_: TimelineFormatException) {
                    // Takeout archives hold other JSON files too; skip anything that isn't a timeline.
                } catch (_: MalformedJsonException) {
                } catch (_: IllegalStateException) {
                }
            }
            coroutineContext.ensureActive()
            entry = zip.nextEntry
        }
        if (!found) throw TimelineFormatException(TimelineFormatException.Reason.EMPTY_ARCHIVE)
    }

    private fun isZip(input: BufferedInputStream): Boolean {
        input.mark(4)
        val header = ByteArray(4)
        val read = input.read(header)
        input.reset()
        return read == 4 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte() &&
            header[2].toInt() == 3 && header[3].toInt() == 4
    }

    private fun query(uri: Uri, column: String): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
        }
    }.getOrNull()

    /** Counts bytes read and reports them, throttled. */
    private class CountingStream(input: InputStream, private val report: (Long) -> Unit) : FilterInputStream(input) {
        var count = 0L
            private set
        private var reported = 0L

        override fun read(): Int = super.read().also { if (it >= 0) advance(1) }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            super.read(buffer, offset, length).also { if (it > 0) advance(it.toLong()) }

        private fun advance(bytes: Long) {
            count += bytes
            if (count - reported > 1 shl 20) {
                reported = count
                report(count)
            }
        }
    }

    /** Lets a parser read one archive entry without closing the whole archive. */
    private class KeepOpen(input: InputStream) : FilterInputStream(input) {
        override fun close() = Unit
    }

    private companion object {
        const val NAME = "name"
        const val POINTS = "points"
        const val IMPORTED_AT = "imported_at"
    }
}
