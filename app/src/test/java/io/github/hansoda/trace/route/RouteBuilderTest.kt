package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.data.TimelineBuilder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteBuilderTest {
    private val minute = 60_000L

    private fun timeline(vararg fixes: Triple<Long, Double, Double>): Timeline {
        val builder = TimelineBuilder()
        fixes.forEach { (time, lat, lon) -> builder.add(time, Math.round(lat * 1e7).toInt(), Math.round(lon * 1e7).toInt(), 0, true) }
        return builder.build()
    }

    @Test
    fun collapsesStandingStillIntoArrivalAndDeparture() {
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        // Two hours at home with ~10 m of GPS jitter, then a walk north.
        for (i in 0..24) fixes += Triple(i * 5 * minute, 48.0 + (if (i % 2 == 0) 0.0001 else -0.0001), 2.0)
        for (i in 1..5) fixes += Triple((120 + i * 3) * minute, 48.0 + i * 0.002, 2.0)
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), 0, Long.MAX_VALUE)

        assertEquals(7, range.size)
        assertEquals(0L, range.times[0])
        assertEquals(120 * minute, range.times[1])
        assertEquals(range.x[0], range.x[1], 0.0)
        assertEquals(120 * minute, range.dwellMs[0])
        assertEquals(1, range.stops)
        // Jitter adds nothing to the distance.
        assertEquals(Geo.haversineMeters(48.0001, 2.0, 48.010, 2.0), range.totalMeters, 1.0)
    }

    @Test
    fun dropsOutAndBackSpikes() {
        val range = RouteBuilder.build(
            timeline(
                Triple(0L, 40.0, -3.0),
                Triple(1 * minute, 40.001, -3.0),
                Triple(2 * minute, 40.3, -3.0), // 33 km away and back within two minutes
                Triple(3 * minute, 40.002, -3.0),
                Triple(4 * minute, 40.003, -3.0),
            ),
            0, Long.MAX_VALUE,
        )
        assertEquals(4, range.size)
        assertTrue(range.totalMeters < 400)
    }

    @Test
    fun keepsRealJourneys() {
        // A flight is fast, but it doesn't come straight back.
        val range = RouteBuilder.build(
            timeline(
                Triple(0L, 51.47, -0.45),
                Triple(90 * minute, 41.30, 2.08),
                Triple(95 * minute, 41.31, 2.09),
            ),
            0, Long.MAX_VALUE,
        )
        assertEquals(3, range.size)
    }

    @Test
    fun selectsTheRequestedNumberOfPoints() {
        val fixes = (0 until 500).map { Triple(it * minute, 10.0 + it * 0.001, 20.0 + kotlin.math.sin(it / 20.0) * 0.01) }
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), 0, Long.MAX_VALUE)
        for (count in listOf(2, 20, 137, range.size)) {
            val route = range.select(count)
            assertEquals(count, route.size)
            assertEquals(range.x[0], route.x[0], 0.0)
            assertEquals(range.x[range.size - 1], route.x[count - 1], 0.0)
            assertEquals(range.totalMeters, route.totalMeters, 1e-6)
            for (i in 1 until route.size) assertTrue(route.times[i] >= route.times[i - 1])
        }
    }

    @Test
    fun keepsPausesWhenSimplifying() {
        val fixes = ArrayList<Triple<Long, Double, Double>>()
        for (i in 0..10) fixes += Triple(i * minute, 0.5 + i * 0.001, 30.0)
        // An hour-long stop in the middle of a straight line.
        fixes += Triple(70 * minute, 0.5 + 10 * 0.001, 30.0)
        for (i in 11..20) fixes += Triple((60 + i) * minute, 0.5 + i * 0.001, 30.0)
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), 0, Long.MAX_VALUE)
        val route = range.select(3)
        assertEquals(60 * minute, route.dwellMs.sum())
    }

    @Test
    fun cutsTheRange() {
        val fixes = (0 until 100).map { Triple(it * minute, 10.0 + it * 0.01, 20.0) }
        val range = RouteBuilder.build(timeline(*fixes.toTypedArray()), 10 * minute, 20 * minute)
        assertEquals(10, range.size)
        assertEquals(10 * minute, range.times[0])
        assertEquals(19 * minute, range.times[9])
    }

    @Test
    fun crossesTheAntimeridianTheShortWay() {
        val range = RouteBuilder.build(
            timeline(Triple(0L, 35.5, 139.8), Triple(600 * minute, 33.9, -118.4)),
            0, Long.MAX_VALUE,
        )
        assertTrue(range.x[1] > 1.0)
        assertTrue(range.x[1] - range.x[0] < 0.5)
    }

    @Test
    fun ranksEndpointsFirstAndCornersNext() {
        val x = doubleArrayOf(0.0, 1.0, 2.0, 3.0, 3.0, 3.0, 3.0)
        val y = doubleArrayOf(0.0, 0.01, 0.0, 0.0, 1.0, 2.0, 3.0)
        val ranks = visvalingamRanks(x, y)
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 4, 5, 6), ranks.sortedArray())
        assertEquals(0, ranks[0])
        assertEquals(1, ranks[6])
        assertEquals(2, ranks[3])
    }

    @Test
    fun budgetIsLogarithmic() {
        assertEquals(10, PointBudget.count(0.5f, 10))
        assertEquals(PointBudget.MIN_POINTS, PointBudget.count(0f, 10_000))
        assertEquals(10_000, PointBudget.count(1f, 10_000))
        assertEquals(447, PointBudget.count(0.5f, 10_000))
    }
}
