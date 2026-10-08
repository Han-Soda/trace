package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.data.TimelineBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionTest {
    private val minute = 60_000L
    private val day = 86_400_000L

    private fun timeline(vararg fixes: Triple<Long, Double, Double>): Timeline {
        val builder = TimelineBuilder()
        fixes.forEach { (time, lat, lon) -> builder.add(time, Math.round(lat * 1e7).toInt(), Math.round(lon * 1e7).toInt(), 0, true) }
        return builder.build()
    }

    // region Days

    @Test
    fun groupsDaysIntoRanges() {
        val days = DaySelection.of(listOf(10L, 3L, 4L, 5L, 12L, 11L))!!
        assertEquals(2, days.rangeCount)
        assertEquals(3L, days.first)
        assertEquals(12L, days.last)
        assertEquals(6, days.dayCount)
        assertTrue(4L in days)
        assertFalse(7L in days)
        assertEquals("3-5,10-12", days.encode())
        assertEquals(days, DaySelection.decode(days.encode()))
    }

    @Test
    fun togglesDays() {
        val days = DaySelection.range(1, 3)
        assertEquals("1-1,3-3", days.toggled(2)!!.encode())
        assertEquals("1-4", days.toggled(4)!!.encode())
        assertNull(DaySelection.range(5, 5).toggled(5))
    }

    @Test
    fun joinsTouchingRangesAndIgnoresDamage() {
        assertEquals("1-9", DaySelection.ofRanges(listOf(5L to 9L, 1L to 4L, 2L to 3L))!!.encode())
        assertNull(DaySelection.decode("garbage"))
        assertNull(DaySelection.decode(""))
        assertEquals(DaySelection.range(20, 26), DaySelection.range(13, 19).shifted(7))
    }

    @Test
    fun keepsStartAndEndTimes() {
        val trip = DaySelection.range(20, 22).withTimes(9 * 60 + 30, 18 * 60)!!
        assertTrue(trip.hasTimes)
        assertFalse(trip.wholeDays().hasTimes)
        assertEquals("20-22@570-1080", trip.encode())
        assertEquals(trip, DaySelection.decode(trip.encode()))
        assertEquals(DaySelection.range(27, 29).withTimes(570, 1080), trip.shifted(7))
        // Picking days one by one is about whole days.
        assertFalse(trip.toggled(25)!!.hasTimes)
        // Settings saved before times existed still read.
        assertEquals(DaySelection.range(20, 22), DaySelection.decode("20-22"))
        assertEquals(DaySelection.range(20, 22), DaySelection.decode("20-22@nonsense"))
    }

    @Test
    fun endsAfterItStarts() {
        assertNull(DaySelection.range(5, 5).withTimes(600, 600))
        assertNull(DaySelection.range(5, 5).withTimes(600, 540))
        assertNull(DaySelection.range(5, 5).withTimes(-1, 540))
        assertNull(DaySelection.range(5, 5).withTimes(0, DaySelection.DAY_MINUTES + 1))
        // Over several days, the last day may end earlier in the day than the first starts.
        assertEquals("5-6@600-540", DaySelection.range(5, 6).withTimes(600, 540)!!.encode())
    }

    @Test
    fun turnsTimesIntoSpans() {
        val dayStart = { day: Long -> day * this.day }
        val timeOn = { day: Long, minute: Int -> day * this.day + minute * this.minute }
        val whole = DaySelection.of(listOf(1L, 2L, 5L))!!
        assertEquals(listOf(day until 3 * day, 5 * day until 6 * day), whole.spans(dayStart, timeOn))
        val timed = DaySelection.range(1, 2).withTimes(9 * 60, 17 * 60)!!
        assertEquals(listOf(day + 9 * 60 * minute until 2 * day + 17 * 60 * minute), timed.spans(dayStart, timeOn))
    }

    // endregion

    // region Removed points

    @Test
    fun mergesRemovedStretches() {
        val removed = Exclusions.NONE.plus(10, 20).plus(30, 30).plus(15, 25).plus(5, 5)
        assertEquals("5-5\n10-25\n30-30", removed.encode())
        assertTrue(10L in removed)
        assertTrue(25L in removed)
        assertFalse(26L in removed)
        assertFalse(4L in removed)
        assertEquals(removed, Exclusions.decode(removed.encode()))
        assertEquals(Exclusions.NONE, Exclusions.decode("nonsense"))
    }

    @Test
    fun skipsRemovedFixes() {
        val fixes = (0 until 10).map { Triple(it * minute, 48.0 + it * 0.01, 2.0) }.toTypedArray()
        val data = timeline(*fixes)
        val removed = Exclusions.NONE.plus(3 * minute, 5 * minute)
        assertEquals(3, removed.countIn(data))
        val range = RouteBuilder.build(data, 0, Long.MAX_VALUE, removed)
        assertEquals(7, range.size)
        assertTrue(range.times.none { it in 3 * minute..5 * minute })
    }

    // endregion

    // region Pieces

    @Test
    fun separateDaysBecomeSeparatePieces() {
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        // Day 0 in Paris, day 2 in Lyon; day 1 isn't chosen.
        for (i in 0..5) fixes += Triple(i * 10 * minute, 48.85 + i * 0.005, 2.35)
        for (i in 0..5) fixes += Triple(day + i * 10 * minute, 46.0, 3.0 + i * 0.01)
        for (i in 0..5) fixes += Triple(2 * day + i * 10 * minute, 45.76 + i * 0.005, 4.84)
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), listOf(0 until day, 2 * day until 3 * day))

        assertEquals(12, range.size)
        assertEquals(listOf(6), range.breakBefore.indices.filter { range.breakBefore[it] })
        // The jump between days adds nothing to the distance travelled.
        assertEquals(range.meters[5], range.meters[6], 0.0)
        assertEquals(0, range.pieceStart(3))
        assertEquals(5, range.pieceEnd(3))
        assertEquals(6, range.pieceStart(9))
        assertEquals(11, range.pieceEnd(9))

        // Even the plainest selection keeps both ends of every piece.
        val route = range.select(2)
        assertEquals(4, route.size)
        assertArrayEquals(booleanArrayOf(false, false, true, false), route.breakBefore)
        assertEquals(range.times[5], route.times[1])
        assertEquals(range.times[6], route.times[2])
    }

    @Test
    fun aStopDoesNotRunAcrossAGap() {
        // Home on both chosen days: the same spot, but a day apart.
        val data = timeline(
            Triple(0L, 10.0, 10.0), Triple(30 * minute, 10.0, 10.0),
            Triple(2 * day, 10.0, 10.0), Triple(2 * day + 30 * minute, 10.0, 10.0),
        )
        val range = RouteBuilder.build(data, listOf(0 until day, 2 * day until 3 * day))
        assertEquals(4, range.size)
        assertTrue(range.breakBefore[2])
        assertEquals(30 * minute, range.dwellMs.max())
    }

    // endregion

    // region Calendar

    @Test
    fun measuresEachDay() {
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        // Day 0: a 2 km walk. Day 1: nothing. Day 2: home all day.
        for (i in 0..20) fixes += Triple(i * minute, 48.0 + i * 0.0009, 2.0)
        fixes += Triple(2 * day, 48.0, 2.0)
        fixes += Triple(2 * day + 600 * minute, 48.0, 2.0)
        val activity = DayActivity.of(timeline(*fixes.toTypedArray()), { it * day }, { Math.floorDiv(it, day) })!!
        assertEquals(0L, activity.firstDay)
        assertEquals(2L, activity.lastDay)
        assertEquals(2000f, activity.meters(0), 20f)
        assertTrue(activity.moved(0))
        assertFalse(activity.hasData(1))
        assertTrue(activity.hasData(2))
        assertFalse(activity.moved(2))
        assertFalse(activity.hasData(3))
    }

    // endregion

    // region Suspects

    @Test
    fun findsGpsErrorsButNotJourneys() {
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        for (i in 0..9) fixes += Triple(i * minute, 52.50 + i * 0.002, 13.40)
        // 2.7 km away and back, a minute each way: too slow for the automatic filter.
        fixes += Triple(10 * minute, 52.519, 13.44)
        fixes += Triple(11 * minute, 52.520, 13.40)
        for (i in 12..20) fixes += Triple(i * minute, 52.50 + i * 0.002, 13.40)
        // A real flight to Rome.
        fixes += Triple(200 * minute, 41.80, 12.25)
        fixes += Triple(210 * minute, 41.81, 12.26)
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), 0, Long.MAX_VALUE)
        val suspects = Suspects.find(range)
        assertEquals(1, suspects.size)
        assertEquals(52.519, io.github.hansoda.trace.data.Geo.lat(range.y[suspects[0]]), 1e-6)
    }

    // endregion
}
