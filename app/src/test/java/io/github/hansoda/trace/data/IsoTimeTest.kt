package io.github.hansoda.trace.data

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IsoTimeTest {
    private val time = IsoTime()

    @Test
    fun parsesOffsetsAndFractions() {
        assertTrue(time.parse("2024-03-01T08:15:30.250+09:00"))
        assertEquals(OffsetDateTime.parse("2024-03-01T08:15:30.250+09:00").toInstant().toEpochMilli(), time.millis)
        assertEquals(540, time.offset)

        assertTrue(time.parse("2024-03-01T08:15:30-0530"))
        assertEquals(OffsetDateTime.parse("2024-03-01T08:15:30-05:30").toInstant().toEpochMilli(), time.millis)
        assertEquals(-330, time.offset)

        assertTrue(time.parse("2024-03-01T08:15:30.123456Z"))
        assertEquals(OffsetDateTime.parse("2024-03-01T08:15:30.123Z").toInstant().toEpochMilli(), time.millis)
        assertEquals(IsoTime.NO_OFFSET, time.offset)
    }

    @Test
    fun parsesEpochMilliseconds() {
        assertTrue(time.parse("1556705400000"))
        assertEquals(1_556_705_400_000L, time.millis)
        assertTrue(time.parse("1556705400"))
        assertEquals(1_556_705_400_000L, time.millis)
    }

    @Test
    fun fallsBackForUnusualShapes() {
        assertTrue(time.parse("2024-03-01T08:15+01:00"))
        assertEquals(OffsetDateTime.parse("2024-03-01T08:15+01:00").toInstant().toEpochMilli(), time.millis)
        assertTrue(time.parse("2024-03-01T08:15:30"))
        assertEquals(OffsetDateTime.parse("2024-03-01T08:15:30Z").toInstant().toEpochMilli(), time.millis)
        assertFalse(time.parse("yesterday"))
        assertFalse(time.parse(""))
    }

    @Test
    fun matchesJavaTimeAcrossCenturies() {
        val random = Random(7)
        repeat(2_000) {
            val date = LocalDate.ofEpochDay(random.nextLong(-40_000, 60_000))
            val offsetMinutes = random.nextInt(-14 * 4, 14 * 4 + 1) * 15
            val instant = OffsetDateTime.of(
                date.year, date.monthValue, date.dayOfMonth,
                random.nextInt(24), random.nextInt(60), random.nextInt(60), random.nextInt(1000) * 1_000_000,
                ZoneOffset.ofTotalSeconds(offsetMinutes * 60),
            )
            assertTrue(instant.toString(), time.parse(instant.toString()))
            assertEquals(instant.toString(), instant.toInstant().toEpochMilli(), time.millis)
        }
    }
}
