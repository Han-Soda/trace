package io.github.hansoda.trace.motion

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.route.Route
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    /** A morning walk in Lisbon, a flight to Berlin and an afternoon there. */
    private fun trip(): Route {
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val times = ArrayList<Long>()
        val dwell = ArrayList<Long>()
        val minute = 60_000L
        var t = 0L
        fun add(lat: Double, lon: Double, step: Long, pause: Long = 0) {
            t += step
            lats += lat
            lons += lon
            times += t
            dwell += pause
            t += pause
        }
        for (i in 0..40) add(38.71 + 0.0004 * i, -9.14 + 0.0003 * kotlin.math.sin(i / 3.0), 2 * minute, if (i == 20) 60 * minute else 0)
        add(38.77, -9.13, 30 * minute)
        add(52.36, 13.50, 180 * minute)
        for (i in 0..30) add(52.52 + 0.0005 * kotlin.math.cos(i / 4.0), 13.40 + 0.0006 * i, 3 * minute)
        val x = DoubleArray(lats.size) { Geo.x(lons[it]) }
        val y = DoubleArray(lats.size) { Geo.y(lats[it]) }
        val meters = DoubleArray(lats.size)
        for (i in 1 until lats.size) meters[i] = meters[i - 1] + Geo.haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(x, y, times.toLongArray(), ShortArray(lats.size) { Timeline.NO_OFFSET }, meters, dwell.toLongArray(), lats.size)
    }

    private fun plan(smoothness: Double, aspect: Double = 9.0 / 16, inset: Double = 0.1) =
        Planner.plan(trip(), MotionSettings(20.0, 30, aspect, smoothness, inset))

    @Test
    fun keepsTheDotInFrame() {
        for (smoothness in listOf(0.0, 0.3, 0.6, 1.0)) {
            for (aspect in listOf(9.0 / 16, 1.0, 16.0 / 9)) {
                val plan = plan(smoothness, aspect)
                for (f in 0 until plan.frameCount) {
                    val width = plan.cameraWidth[f]
                    val height = width / aspect
                    val dx = abs(plan.headX[f] - plan.cameraX[f]) / width
                    val dy = (plan.headY[f] - plan.cameraY[f]) / height
                    assertTrue("smoothness $smoothness aspect $aspect frame $f dx $dx", dx <= 0.5)
                    assertTrue("smoothness $smoothness aspect $aspect frame $f dy $dy", dy in -0.5 + 0.1..0.5)
                }
            }
        }
    }

    @Test
    fun smootherSettingsZoomMoreGently() {
        fun steepestZoom(plan: Plan): Double {
            var steepest = 0.0
            for (f in 1 until plan.frameCount) {
                steepest = max(steepest, abs(log2(plan.cameraWidth[f]) - log2(plan.cameraWidth[f - 1])) * plan.fps)
            }
            return steepest
        }
        val sharp = steepestZoom(plan(0.0))
        val default = steepestZoom(plan(0.6))
        val smooth = steepestZoom(plan(1.0))
        assertTrue("sharp $sharp default $default", default < sharp)
        assertTrue("default $default smooth $smooth", smooth < default)
        assertTrue("smooth $smooth", smooth < 6)
    }

    @Test
    fun smootherSettingsReframeLessOften() {
        assertTrue(plan(1.0).sceneCount <= plan(0.6).sceneCount)
        assertTrue(plan(0.6).sceneCount <= plan(0.0).sceneCount)
        assertTrue(plan(0.6).sceneCount >= 3)
    }

    @Test
    fun cameraHoldsStillWithinAScene() {
        val plan = plan(0.6)
        var still = 0
        for (f in 1 until plan.frameCount) {
            if (plan.cameraWidth[f] == plan.cameraWidth[f - 1] && plan.cameraX[f] == plan.cameraX[f - 1]) still++
        }
        assertTrue("still $still of ${plan.frameCount}", still > plan.frameCount / 3)
    }

    @Test
    fun zoomHasNoKinks() {
        val plan = plan(0.6)
        var steepestChange = 0.0
        for (f in 2 until plan.frameCount) {
            val a = log2(plan.cameraWidth[f - 2])
            val b = log2(plan.cameraWidth[f - 1])
            val c = log2(plan.cameraWidth[f])
            steepestChange = max(steepestChange, abs(c - 2 * b + a) * plan.fps * plan.fps)
        }
        // Zoom acceleration in levels per second squared.
        assertTrue("acceleration $steepestChange", steepestChange < 12)
    }

    @Test
    fun walksTheRouteInOrderAndEndsOnTheOverview() {
        val plan = plan(0.6)
        val route = plan.route
        for (f in 1 until plan.frameCount) {
            assertTrue(plan.headSegment[f] >= plan.headSegment[f - 1])
            assertTrue(plan.headMeters[f] >= plan.headMeters[f - 1] - 1e-6)
            assertTrue(plan.headTime[f] >= plan.headTime[f - 1])
        }
        assertEquals(route.size - 1, plan.headSegment[plan.frameCount - 1])
        assertEquals(route.totalMeters, plan.headMeters[plan.frameCount - 1], 1e-6)
        val last = plan.frameCount - 1
        val bounds = route.bounds()
        val width = plan.cameraWidth[last]
        val height = width / (9.0 / 16)
        assertTrue(bounds.minX >= plan.cameraX[last] - width / 2 && bounds.maxX <= plan.cameraX[last] + width / 2)
        assertTrue(bounds.minY >= plan.cameraY[last] - height / 2 && bounds.maxY <= plan.cameraY[last] + height / 2)
        // The last frames hold still.
        assertEquals(plan.cameraWidth[last], plan.cameraWidth[last - 10], plan.cameraWidth[last] * 1e-9)
    }

    private fun pace(route: Route): Planner.Pace {
        val scenes = Planner.scenes(route, Planner.Framer(9.0 / 16, 0.1), 0.6)
        return Planner.pace(route, scenes, 15.0, 0.6)
    }

    @Test
    fun pausesAtLongStops() {
        val route = trip()
        val pace = pace(route)
        assertTrue(pace.depart[20] - pace.arrive[20] > 0.05)
        assertEquals(0.0, pace.depart[5] - pace.arrive[5], 0.0)
        assertEquals(15.0, pace.arrive[route.size - 1], 1e-9)
        for (i in 1 until route.size) assertTrue(pace.arrive[i] >= pace.depart[i - 1])
    }

    @Test
    fun theFlightGetsAFairShare() {
        val route = trip()
        val pace = pace(route)
        val flight = pace.arrive[42] - pace.depart[41]
        assertTrue("flight $flight", flight in 0.5..4.5)
        // The camera pulls out before take-off and dives in after landing while the dot waits.
        assertTrue(pace.depart[41] - pace.arrive[41] > 1.0)
        assertTrue(pace.depart[42] - pace.arrive[42] > 1.0)
    }

    @Test
    fun plansAYearOfStopsQuickly() {
        // A year of commuting: 20,000 points and a stop every ~13 of them.
        val n = 20_000
        val random = kotlin.random.Random(3)
        val lats = DoubleArray(n)
        val lons = DoubleArray(n)
        for (i in 0 until n) {
            val day = i / 55
            lats[i] = 48.85 + 0.03 * kotlin.math.sin(i / 7.0) + (if (day % 60 == 0) 2.0 else 0.0) + random.nextDouble() * 0.001
            lons[i] = 2.35 + 0.04 * kotlin.math.cos(i / 9.0) + random.nextDouble() * 0.001
        }
        val meters = DoubleArray(n)
        for (i in 1 until n) meters[i] = meters[i - 1] + Geo.haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
        val route = Route(
            DoubleArray(n) { Geo.x(lons[it]) }, DoubleArray(n) { Geo.y(lats[it]) },
            LongArray(n) { it * 25 * 60_000L }, ShortArray(n) { Timeline.NO_OFFSET }, meters,
            LongArray(n) { if (it % 13 == 0) 3 * 3_600_000L else 0 }, n,
        )
        val started = System.nanoTime()
        val plan = Planner.plan(route, MotionSettings(60.0, 60, 9.0 / 16, 0.6, 0.1))
        val millis = (System.nanoTime() - started) / 1_000_000
        assertTrue("took $millis ms", millis < 3_000)
        assertEquals(3_600, plan.frameCount)
    }

    @Test
    fun transitionsKeepSharedPointsInView() {
        val from = Framing(0.5, 0.5, 0.001)
        val to = Framing(0.6, 0.45, 0.2)
        val point = doubleArrayOf(0.5003, 0.5002)
        for (step in 0..100) {
            val f = Planner.between(from, to, step / 100.0)
            assertTrue(abs(point[0] - f.x) <= f.width / 2)
            assertTrue(abs(point[1] - f.y) <= f.width / 2)
        }
        for ((expected, actual) in listOf(to to Planner.between(from, to, 1.0), from to Planner.between(from, to, 0.0))) {
            assertEquals(expected.x, actual.x, 1e-12)
            assertEquals(expected.y, actual.y, 1e-12)
            assertEquals(expected.width, actual.width, 1e-12)
        }
    }
}
