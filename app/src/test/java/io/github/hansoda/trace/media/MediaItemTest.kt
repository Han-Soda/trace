package io.github.hansoda.trace.media

import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaItemTest {
    @Test
    fun indexKeepsEveryItem() {
        val items = listOf(
            MediaItem("photo", 1_749_286_800_000, 1, 0.0, 1440, 1080, place = "Navy Pier, Chicago", placeLanguage = "en"),
            MediaItem(
                "clip", 1_749_290_400_000, 150, 30.0, 540, 960,
                source = "content://media/picker/0/com.android.providers.media.photopicker/media/1000000042",
                sourceMs = 62_000, startMs = 12_500, audio = true,
            ),
        )
        assertEquals(items, MediaIndex.decode(MediaIndex.encode(items)))
        assertEquals(5.0, items[1].seconds, 1e-9)
        assertEquals(1_749_290_412_500, items[1].shownTime)
        assertTrue(items[1].canTrim)
        // A damaged line is skipped, not the whole list.
        assertEquals(items, MediaIndex.decode(MediaIndex.encode(items) + "\nbroken\tline"))
        // Tabs in a place name can't break the line up.
        val tabbed = items[0].copy(place = "Pier\tone")
        assertEquals("Pier one", MediaIndex.decode(MediaIndex.encode(listOf(tabbed))).single().place)
    }

    @Test
    fun readsTheIndexOfEarlierVersions() {
        val old = "clip\t1749290400000\t36\t12.0\t720\t1280"
        val item = MediaIndex.decode(old).single()
        assertEquals(MediaItem("clip", 1_749_290_400_000, 36, 12.0, 720, 1280), item)
        assertEquals(3.0, item.seconds, 1e-9)
        assertFalse(item.canTrim)
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
