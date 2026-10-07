package io.github.hansoda.trace.media

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import java.util.Locale

/** Finds the photos and videos in the phone's gallery taken during some stretches of time. */
class MediaFinder(context: Context) {
    private val resolver = context.applicationContext.contentResolver

    /** A photo or video in the gallery, and when it was taken. */
    class Found(val uri: Uri, val time: Long, val video: Boolean, val durationMs: Long)

    /**
     * Everything taken during [spans] of UTC milliseconds, oldest first, leaving out
     * screenshots, screen recordings and videos too short to show.
     */
    fun find(spans: List<LongRange>): List<Found> {
        if (spans.isEmpty()) return emptyList()
        val found = ArrayList<Found>()
        query(collection(video = false), video = false, spans, found)
        query(collection(video = true), video = true, spans, found)
        return found.sortedBy { it.time }
    }

    /** A small picture of [found] for choosing, or null. */
    fun thumbnail(found: Found, side: Int): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.loadThumbnail(found.uri, Size(side, side), null)
        } else {
            val id = ContentUris.parseId(found.uri)
            @Suppress("DEPRECATION")
            if (found.video) {
                MediaStore.Video.Thumbnails.getThumbnail(resolver, id, MediaStore.Video.Thumbnails.MINI_KIND, null)
            } else {
                MediaStore.Images.Thumbnails.getThumbnail(resolver, id, MediaStore.Images.Thumbnails.MINI_KIND, null)
            }
        }
    }.getOrNull()

    private fun collection(video: Boolean): Uri = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && video -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        video -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        else -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }

    private fun query(collection: Uri, video: Boolean, spans: List<LongRange>, into: MutableList<Found>) {
        // Days next to each other are one stretch already; a few hundred stretches are fine to
        // ask about, more are filtered here.
        val asked = if (spans.size <= MAX_SPANS) spans else listOf(spans.minOf { it.first }..spans.maxOf { it.last })
        val selection = asked.joinToString(" OR ") { "($TAKEN >= ? AND $TAKEN <= ?)" }
        val arguments = asked.flatMap { listOf(it.first.toString(), it.last.toString()) }.toTypedArray()
        val folder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else DATA
        val projection = if (video) arrayOf(MediaStore.MediaColumns._ID, TAKEN, folder, DURATION) else arrayOf(MediaStore.MediaColumns._ID, TAKEN, folder)
        runCatching {
            resolver.query(collection, projection, selection, arguments, "$TAKEN ASC")?.use { cursor ->
                while (cursor.moveToNext()) {
                    val taken = cursor.getLong(1).takeIf { it > 0 } ?: continue
                    if (spans.size > MAX_SPANS && spans.none { taken in it }) continue
                    val path = cursor.getString(2).orEmpty().lowercase(Locale.ROOT)
                    if (SKIPPED.any { it in path }) continue
                    val duration = if (video) cursor.getLong(3) else 0L
                    if (video && duration < MediaLibrary.MIN_CLIP_MS) continue
                    into += Found(ContentUris.withAppendedId(collection, cursor.getLong(0)), taken, video, duration)
                }
            }
        }
    }

    private companion object {
        const val TAKEN = "datetaken"
        const val DATA = "_data"
        const val DURATION = "duration"
        const val MAX_SPANS = 400

        /** Folders of things that aren't photos of the trip. */
        val SKIPPED = listOf("screenshot", "screen record", "screenrecord", "screencast")
    }
}
