package io.github.hansoda.trace.media

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaItemTest {
    @Test
    fun indexKeepsEveryItem() {
        val items = listOf(
            MediaItem("photo", 1_749_286_800_000, 1, 0.0, 1440, 1080),
            MediaItem("clip", 1_749_290_400_000, 36, 12.0, 720, 1280),
        )
        assertEquals(items, MediaIndex.decode(MediaIndex.encode(items)))
        assertEquals(3.0, items[1].seconds, 1e-9)
        // A damaged line is skipped, not the whole list.
        assertEquals(items, MediaIndex.decode(MediaIndex.encode(items) + "\nbroken\tline"))
    }

    @Test
    fun readsWhenPhotosWereTaken() {
        val moscow = ZoneId.of("Europe/Moscow")
        val expected = ZonedDateTime.of(2025, 6, 7, 14, 5, 12, 0, moscow).toInstant().toEpochMilli()
        // The photo's own offset wins over the zone it's read in.
        assertEquals(expected, MediaTime.exif("2025:06:07 14:05:12", "+03:00", ZoneId.of("UTC")))
        // Without one, it's in the zone given.
        assertEquals(expected, MediaTime.exif("2025:06:07 14:05:12", null, moscow))
        assertNull(MediaTime.exif("    :  :     :  :  ", null, moscow))
        assertNull(MediaTime.exif(null, null, moscow))
    }

    @Test
    fun readsWhenVideosWereTaken() {
        val expected = ZonedDateTime.of(2025, 6, 7, 11, 5, 12, 0, ZoneId.of("UTC")).toInstant().toEpochMilli()
        assertEquals(expected, MediaTime.video("20250607T110512.000Z"))
        // Cameras without a clock.
        assertNull(MediaTime.video("19040101T000000.000Z"))
        assertNull(MediaTime.video(""))
    }
}
