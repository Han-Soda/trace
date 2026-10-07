package io.github.hansoda.trace.media

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.webkit.MimeTypeMap
import java.io.File
import java.io.IOException
import java.time.ZoneId
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The photos and clips added to Trace. Each is copied in, small, as JPEG frames: it stays
 * whatever happens to the original, and is quick to draw. A clip keeps part of its video, up
 * to [MAX_CLIP_MS], with its sound; another part can be chosen while the video is still there.
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

    fun thumbnailFile(id: String): File = File(File(root, id), THUMBNAIL)

    /** A clip's sound, as [Sound] keeps it. */
    fun soundFile(id: String): File = File(File(root, id), SOUND)

    fun remove(item: MediaItem) {
        File(root, item.id).deleteRecursively()
        item.source?.let { source ->
            runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(source), Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
    }

    /**
     * Copies in photos and videos. Ones taken at the same moment as one already [kept] are
     * skipped, and so are ones with no date, which couldn't be put on the route.
     */
    suspend fun add(uris: List<Uri>, kept: List<MediaItem>, onProgress: (done: Int, total: Int) -> Unit): Added = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val known = kept.mapTo(HashSet()) { it.time }
        val added = ArrayList<MediaItem>()
        var undated = 0
        var unreadable = 0
        uris.forEachIndexed { done, uri ->
            onProgress(done, uris.size)
            coroutineContext.ensureActive()
            val video = typeOf(uri).startsWith("video/")
            val time = takenAt(uri, video)
            when {
                time == null -> undated++
                time in known -> Unit
                else -> {
                    val id = "m" + time.toString(36) + Random.nextInt(1 shl 20).toString(36)
                    val folder = File(root, id)
                    val item = runCatching {
                        if (video) copyClip(uri, id, time, null, null, folder) { job?.isActive != false } else copyPhoto(uri, id, time, folder)
                    }.getOrNull()
                    if (item == null) {
                        folder.deleteRecursively()
                        unreadable++
                    } else {
                        if (item.source != null) keepAccess(uri)
                        added += item
                        known += time
                    }
                }
            }
        }
        onProgress(uris.size, uris.size)
        Added(added, undated, unreadable)
    }

    /**
     * Keeps [lengthMs] of [item]'s video from [startMs] instead of the part it has now. Throws
     * when the video is gone or can't be read; the clip then stays as it was.
     */
    suspend fun trim(item: MediaItem, startMs: Long, lengthMs: Long, onProgress: (Float) -> Unit): MediaItem = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val source = item.source?.let(Uri::parse) ?: throw IOException("The original video isn't known")
        val temporary = File(root, item.id + ".part")
        temporary.deleteRecursively()
        try {
            val trimmed = copyClip(source, item.id, item.time, startMs, lengthMs, temporary, onProgress) { job?.isActive != false }
                ?: throw IOException("Couldn't read the video")
            coroutineContext.ensureActive()
            val folder = File(root, item.id)
            folder.deleteRecursively()
            if (!temporary.renameTo(folder)) throw IOException("Couldn't keep the new part")
            // Another part may have been taken somewhere else.
            trimmed.copy(place = null, placeLanguage = null)
        } finally {
            temporary.deleteRecursively()
        }
    }

    /** What [uri] holds, from its provider or else from its name. */
    private fun typeOf(uri: Uri): String = context.contentResolver.getType(uri)
        ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(uri.toString()).lowercase())
        ?: ""

    /** Keeps the right to read a video, to choose another part of it later. */
    private fun keepAccess(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
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

    private fun copyPhoto(uri: Uri, id: String, time: Long, folder: File): MediaItem? {
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
        folder.mkdirs()
        write(photo, File(folder, "0.jpg"), PHOTO_QUALITY)
        writeThumbnail(photo, folder)
        return MediaItem(id, time, 1, 0.0, photo.width, photo.height).also { photo.recycle() }
    }

    /**
     * Copies [lengthMs] of a video from [startMs] into [folder]: by default a few seconds after
     * the first moment, often a shaky start.
     */
    private fun copyClip(
        uri: Uri, id: String, time: Long, startMs: Long?, lengthMs: Long?, folder: File,
        onProgress: (Float) -> Unit = {}, isActive: () -> Boolean,
    ): MediaItem? {
        val duration = withRetriever(uri) { it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() }
            ?.takeIf { it > 0 } ?: return null
        val start = (startMs ?: min(SKIP_MS, duration / 10)).coerceIn(0, max(0, duration - MIN_CLIP_MS))
        val length = (lengthMs ?: DEFAULT_CLIP_MS).coerceIn(MIN_CLIP_MS, MAX_CLIP_MS).coerceAtMost(duration - start)
        if (length <= 0) return null
        folder.mkdirs()
        val reader = ClipReader(context, CLIP_SIDE, CLIP_QUALITY)
        val startUs = start * 1000
        val endUs = (start + length) * 1000
        val frames = runCatching {
            reader.frames(uri, startUs, endUs, { File(folder, "$it.jpg") }, { writeThumbnail(it, folder) }, isActive) { onProgress(it * 0.9f) }
        }.getOrNull() ?: framesOneByOne(uri, start, length, folder) ?: return null
        val sound = runCatching { reader.sound(uri, startUs, endUs, isActive) }.getOrNull()
        if (sound != null) Sound.write(File(folder, SOUND), sound)
        onProgress(1f)
        return MediaItem(
            id, time, frames.count, frames.fps, frames.width, frames.height,
            source = uri.toString(), sourceMs = duration, startMs = start, audio = sound != null,
        )
    }

    /** For videos the decoder can't take one picture after another: slower, and fewer pictures. */
    private fun framesOneByOne(uri: Uri, start: Long, length: Long, folder: File): ClipReader.Frames? = withRetriever(uri) { retriever ->
        val count = max(1L, length * SLOW_FPS / 1000).toInt()
        var kept = 0
        var width = 0
        var height = 0
        for (k in 0 until count) {
            val atMicros = (start + k * 1000L / SLOW_FPS) * 1000
            val frame = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST, CLIP_SIDE, CLIP_SIDE)
            } else {
                retriever.getFrameAtTime(atMicros, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: continue
            val small = shrink(frame, CLIP_SIDE)
            write(small, File(folder, "$kept.jpg"), CLIP_QUALITY)
            if (kept == 0) writeThumbnail(small, folder)
            width = small.width
            height = small.height
            small.recycle()
            kept++
        }
        if (kept == 0) null else ClipReader.Frames(kept, kept * 1000.0 / length, width, height)
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

    private fun writeThumbnail(bitmap: Bitmap, folder: File) {
        val side = min(bitmap.width, bitmap.height)
        val square = Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
        val small = if (side > THUMBNAIL_SIDE) Bitmap.createScaledBitmap(square, THUMBNAIL_SIDE, THUMBNAIL_SIDE, true) else square
        write(small, File(folder, THUMBNAIL), THUMBNAIL_QUALITY)
        if (small !== bitmap) small.recycle()
        if (square !== bitmap && square !== small) square.recycle()
    }

    private fun write(bitmap: Bitmap, file: File, quality: Int) {
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    // endregion

    companion object {
        /** Longest part of a video a clip keeps. */
        const val MAX_CLIP_MS = 10_000L
        const val MIN_CLIP_MS = 1_000L
        const val DEFAULT_CLIP_MS = 5_000L

        /** Where the photo picker and the gallery keep the moment a picture was taken. */
        private val TAKEN_COLUMNS = arrayOf("date_taken_ms", "datetaken")
        private const val OFFSET_ORIGINAL = "OffsetTimeOriginal"
        private const val OFFSET = "OffsetTime"

        private const val THUMBNAIL = "thumb.jpg"
        private const val SOUND = "sound.pcm"
        private const val PHOTO_SIDE = 1440
        private const val PHOTO_QUALITY = 88
        private const val CLIP_SIDE = 960
        private const val CLIP_QUALITY = 80
        private const val SKIP_MS = 500L
        private const val SLOW_FPS = 12
        private const val THUMBNAIL_SIDE = 192
        private const val THUMBNAIL_QUALITY = 85
    }
}
