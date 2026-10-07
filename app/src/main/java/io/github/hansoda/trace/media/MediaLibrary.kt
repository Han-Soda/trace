package io.github.hansoda.trace.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import java.io.File
import java.time.ZoneId
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * The photos and clips added to Trace. Each is copied in, small, as JPEG frames: it stays
 * whatever happens to the original, and is quick to draw. A clip keeps its first seconds.
 */
class MediaLibrary(context: Context) {
    private val context = context.applicationContext
    private val root = File(context.filesDir, "media")
    private val index = File(root, "index.txt")

    /** What came of adding some photos and videos. */
    class Added(val items: List<MediaItem>, val undated: Int, val unreadable: Int)

    /** Everything kept, in the order it was taken. */
    fun load(): List<MediaItem> =
        runCatching { if (index.exists()) MediaIndex.decode(index.readText()) else emptyList() }.getOrDefault(emptyList()).sortedBy { it.time }

    fun save(items: List<MediaItem>) {
        root.mkdirs()
        val partial = File(root, "index.txt.part")
        partial.writeText(MediaIndex.encode(items))
        partial.renameTo(index)
    }

    fun frameFile(id: String, frame: Int): File = File(File(root, id), "$frame.jpg")

    fun thumbnailFile(id: String): File = File(File(root, id), "thumb.jpg")

    fun remove(id: String) {
        File(root, id).deleteRecursively()
    }

    /**
     * Copies in photos and videos. Ones taken at the same moment as one already [kept] are
     * skipped, and so are ones with no date, which couldn't be put on the route.
     */
    suspend fun add(uris: List<Uri>, kept: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit): Added = withContext(Dispatchers.IO) {
        val known = kept.mapTo(HashSet()) { it.time }
        val added = ArrayList<MediaItem>()
        var undated = 0
        var unreadable = 0
        uris.forEachIndexed { done, uri ->
            onProgress(done, uris.size)
            coroutineContext.ensureActive()
            val video = context.contentResolver.getType(uri)?.startsWith("video/") == true
            val time = takenAt(uri, video)
            when {
                time == null -> undated++
                time in known -> Unit
                else -> {
                    val id = "m" + time.toString(36) + Random.nextInt(1 shl 20).toString(36)
                    val item = runCatching { if (video) copyClip(uri, id, time) else copyPhoto(uri, id, time) }.getOrNull()
                    if (item == null) {
                        remove(id)
                        unreadable++
                    } else {
                        added += item
                        known += time
                    }
                }
            }
        }
        onProgress(uris.size, uris.size)
        Added(added, undated, unreadable)
    }

    // region When

    /** When a photo or video was taken, in UTC milliseconds, if anything says. */
    private fun takenAt(uri: Uri, video: Boolean): Long? {
        // The photo picker and the gallery know, for anything taken on a phone.
        for (column in TAKEN_COLUMNS) {
            val taken = runCatching {
                context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull()
            if (taken != null && taken > 0) return taken
        }
        return if (video) videoTakenAt(uri) else photoTakenAt(uri)
    }

    private fun photoTakenAt(uri: Uri): Long? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val exif = ExifInterface(input)
            MediaTime.exif(
                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif.getAttribute(ExifInterface.TAG_DATETIME),
                exif.getAttribute(OFFSET_ORIGINAL) ?: exif.getAttribute(OFFSET),
                ZoneId.systemDefault(),
            )
        }
    }.getOrNull()

    private fun videoTakenAt(uri: Uri): Long? = withRetriever(uri) { retriever ->
        MediaTime.video(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE))
    }

    // endregion

    // region Copying

    private fun copyPhoto(uri: Uri, id: String, time: Long): MediaItem? {
        val resolver = context.contentResolver
        // Only the size: this decode returns no bitmap.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        (resolver.openInputStream(uri) ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= PHOTO_SIDE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val orientation = runCatching {
            resolver.openInputStream(uri)?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val photo = upright(shrink(decoded, PHOTO_SIDE), orientation)
        File(root, id).mkdirs()
        write(photo, frameFile(id, 0), PHOTO_QUALITY)
        writeThumbnail(photo, id)
        return MediaItem(id, time, 1, 0.0, photo.width, photo.height).also { photo.recycle() }
    }

    private fun copyClip(uri: Uri, id: String, time: Long): MediaItem? = withRetriever(uri) { retriever ->
        val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return@withRetriever null
        // Skip the first moment, often a shaky start, then keep a few seconds.
        val start = min(500L, duration / 10)
        val length = min(CLIP_MS, duration - start)
        val count = max(1L, length * CLIP_FPS / 1000).toInt()
        File(root, id).mkdirs()
        var kept = 0
        var width = 0
        var height = 0
        for (k in 0 until count) {
            val atMicros = (start + k * 1000L / CLIP_FPS) * 1000
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST, CLIP_SIDE, CLIP_SIDE)
            } else {
                retriever.getFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: continue
            val small = shrink(frame, CLIP_SIDE)
            write(small, frameFile(id, kept), CLIP_QUALITY)
            if (kept == 0) writeThumbnail(small, id)
            width = small.width
            height = small.height
            small.recycle()
            kept++
        }
        if (kept == 0) null else MediaItem(id, time, kept, CLIP_FPS.toDouble(), width, height)
    }

    private fun <T> withRetriever(uri: Uri, use: (MediaMetadataRetriever) -> T?): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            use(retriever)
        } catch (_: RuntimeException) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** [bitmap] no larger than [side] on its long side; recycles the original if it had to shrink it. */
    private fun shrink(bitmap: Bitmap, side: Int): Bitmap {
        val long = max(bitmap.width, bitmap.height)
        if (long <= side) return bitmap
        val scale = side.toFloat() / long
        val small = Bitmap.createScaledBitmap(bitmap, max(1, (bitmap.width * scale).toInt()), max(1, (bitmap.height * scale).toInt()), true)
        if (small !== bitmap) bitmap.recycle()
        return small
    }

    /** Turns a photo the way its camera says it was held. */
    private fun upright(bitmap: Bitmap, orientation: Int): Bitmap {
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (turned !== bitmap) bitmap.recycle()
        return turned
    }

    private fun writeThumbnail(bitmap: Bitmap, id: String) {
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val small = if (side > THUMBNAIL_SIDE) Bitmap.createScaledBitmap(square, THUMBNAIL_SIDE, THUMBNAIL_SIDE, true) else square
        write(small, thumbnailFile(id), THUMBNAIL_QUALITY)
        if (small !== bitmap) small.recycle()
        if (square !== bitmap && square !== small) square.recycle()
    }

    private fun write(bitmap: Bitmap, file: File, quality: Int) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    // endregion

    private companion object {
        /** Where the photo picker and the gallery keep the moment a picture was taken. */
        val TAKEN_COLUMNS = arrayOf("date_taken_ms", "datetaken")
        const val OFFSET_ORIGINAL = "OffsetTimeOriginal"
        const val OFFSET = "OffsetTime"

        const val PHOTO_SIDE = 1440
        const val PHOTO_QUALITY = 88
        const val CLIP_SIDE = 720
        const val CLIP_QUALITY = 82
        const val CLIP_FPS = 12
        const val CLIP_MS = 3_000L
        const val THUMBNAIL_SIDE = 192
        const val THUMBNAIL_QUALITY = 85
    }
}
