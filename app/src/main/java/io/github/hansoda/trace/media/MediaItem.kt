package io.github.hansoda.trace.media

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/** A photo, or the first seconds of a video, kept in Trace's own storage as JPEG frames. */
data class MediaItem(
    val id: String,
    /** When it was taken, in UTC milliseconds. */
    val time: Long,
    /** 1 for a photo; a clip's frames. */
    val frames: Int,
    /** A clip's frames per second; 0 for a photo. */
    val fps: Double,
    val width: Int,
    val height: Int,
) {
    val isClip: Boolean get() = frames > 1

    /** How long a clip plays. */
    val seconds: Double get() = if (isClip && fps > 0) frames / fps else 0.0
}

/** The list of kept photos and clips, one tab-separated line each. */
object MediaIndex {
    fun encode(items: List<MediaItem>): String = items.joinToString("\n") { item ->
        listOf(item.id, item.time, item.frames, item.fps, item.width, item.height).joinToString("\t")
    }

    fun decode(text: String): List<MediaItem> = text.lineSequence().mapNotNull { line ->
        val parts = line.split('\t')
        if (parts.size < 6) return@mapNotNull null
        MediaItem(
            id = parts[0].takeIf { it.isNotBlank() } ?: return@mapNotNull null,
            time = parts[1].toLongOrNull() ?: return@mapNotNull null,
            frames = parts[2].toIntOrNull()?.takeIf { it > 0 } ?: return@mapNotNull null,
            fps = parts[3].toDoubleOrNull() ?: 0.0,
            width = parts[4].toIntOrNull() ?: 0,
            height = parts[5].toIntOrNull() ?: 0,
        )
    }.toList()
}

/** When a photo or video was taken, from the ways files and Android record it. */
object MediaTime {
    private val EXIF = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    private val VIDEO = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")

    /**
     * An EXIF date such as "2025:05:06 14:05:12", in UTC: with the photo's own offset, such as
     * "+03:00", when it has one, otherwise in [zone].
     */
    fun exif(date: String?, offset: String?, zone: ZoneId): Long? {
        val local = try {
            LocalDateTime.parse(date?.trim()?.take(19) ?: return null, EXIF)
        } catch (_: DateTimeParseException) {
            return null
        }
        val own = offset?.trim()?.let { runCatching { ZoneOffset.of(it) }.getOrNull() }
        return plausible(local.atZone(own ?: zone).toInstant().toEpochMilli())
    }

    /** A video's date, such as "20250506T140512.000Z", which Android reports in UTC. */
    fun video(date: String?): Long? {
        val text = date?.trim() ?: return null
        if (text.length < 15) return null
        val local = try {
            LocalDateTime.parse(text.take(15), VIDEO)
        } catch (_: DateTimeParseException) {
            return null
        }
        return plausible(local.toInstant(ZoneOffset.UTC).toEpochMilli())
    }

    /** Cameras without a clock say 1904 or 1970; nothing taken before 1990 belongs on a Timeline. */
    private fun plausible(time: Long): Long? = time.takeIf { it > 631_152_000_000L }
}
