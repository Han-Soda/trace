package io.github.hansoda.trace.motion

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.route.Route
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        return route(lats, lons, times.toLongArray(), dwell.toLongArray())
    }

    private fun route(lats: List<Double>, lons: List<Double>, times: LongArray, dwell: LongArray, breaks: Set<Int> = emptySet()): Route {
        val x = DoubleArray(lats.size) { Geo.x(lons[it]) }
        val y = DoubleArray(lats.size) { Geo.y(lats[it]) }
        val meters = DoubleArray(lats.size)
        for (i in 1 until lats.size) {
            meters[i] = meters[i - 1] + if (i in breaks) 0.0 else Geo.haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
        }
        return Route(
            x, y, times, ShortArray(lats.size) { Timeline.NO_OFFSET }, meters, dwell, lats.size,
            BooleanArray(lats.size) { it in breaks },
        )
    }

    private fun plan(
        smoothness: Double = 0.6,
        camera: CameraMode = CameraMode.FOLLOW,
        aspect: Double = 9.0 / 16,
        inset: Double = 0.1,
        pause: Boolean = false,
        route: Route = trip(),
    ) = Planner.plan(route, MotionSettings(20.0, 30, aspect, smoothness, inset, camera, pause))

    @Test
    fun keepsTheDotInFrame() {
        for (camera in CameraMode.entries) {
            for (smoothness in listOf(0.0, 0.3, 0.6, 1.0)) {
                for (aspect in listOf(9.0 / 16, 1.0, 16.0 / 9)) {
                    for (pause in listOf(false, true)) {
                        val plan = plan(smoothness, camera, aspect, pause = pause)
                        for (f in 0 until plan.frameCount) {
                            val width = plan.cameraWidth[f]
                            val height = width / aspect
                            val dx = abs(plan.headX[f] - plan.cameraX[f]) / width
                            val dy = (plan.headY[f] - plan.cameraY[f]) / height
                            val where = "$camera smoothness $smoothness aspect $aspect pause $pause frame $f"
                            assertTrue("$where dx $dx", dx <= 0.5)
                            assertTrue("$where dy $dy", dy in -0.5 + 0.1..0.5)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun keepsMovingUnlessAskedToPause() {
        for (camera in CameraMode.entries) {
            val plan = plan(camera = camera)
            var still = 0
            for (f in 1..plan.endingFrame) {
                if (plan.headX[f] == plan.headX[f - 1] && plan.headY[f] == plan.headY[f - 1]) still++
            }
            assertEquals("$camera", 0, still)

            val pausing = plan(camera = camera, pause = true)
            var waited = 0
            for (f in 1..pausing.endingFrame) {
                if (pausing.headX[f] == pausing.headX[f - 1] && pausing.headY[f] == pausing.headY[f - 1]) waited++
            }
            // The hour-long stop in Lisbon.
            assertTrue("$camera waited $waited", waited >= 8)
        }
    }

    @Test
    fun followCameraTravelsWithTheDot() {
        val plan = plan(camera = CameraMode.FOLLOW)
        var moving = 0
        for (f in 1..plan.endingFrame) {
            if (plan.cameraX[f] != plan.cameraX[f - 1] || plan.cameraY[f] != plan.cameraY[f - 1]) moving++
        }
        assertTrue("moving $moving of ${plan.endingFrame}", moving >= plan.endingFrame * 0.95)
        // The dot stays near the middle, a little behind where the camera looks, until the
        // camera starts easing out to the whole route.
        for (f in 0..(plan.endingFrame * 0.8).toInt()) {
            val dx = abs(plan.headX[f] - plan.cameraX[f]) / plan.cameraWidth[f]
            assertTrue("frame $f dx $dx", dx <= 0.36)
        }
    }

    @Test
    fun shotsHoldStillWithinAScene() {
        val plan = plan(camera = CameraMode.SHOTS)
        var still = 0
        for (f in 1 until plan.frameCount) {
            if (plan.cameraWidth[f] == plan.cameraWidth[f - 1] && plan.cameraX[f] == plan.cameraX[f - 1]) still++
        }
        assertTrue("still $still of ${plan.frameCount}", still > plan.frameCount / 3)
    }

    @Test
    fun wholeRouteStaysInView() {
        val plan = plan(camera = CameraMode.WHOLE)
        val bounds = plan.route.bounds()
        for (f in 0 until plan.frameCount) {
            assertEquals(plan.cameraWidth[0], plan.cameraWidth[f], 0.0)
            assertTrue(bounds.minX >= plan.cameraX[f] - plan.cameraWidth[f] / 2)
            assertTrue(bounds.maxX <= plan.cameraX[f] + plan.cameraWidth[f] / 2)
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
        for (camera in listOf(CameraMode.FOLLOW, CameraMode.SHOTS)) {
            val sharp = steepestZoom(plan(0.0, camera))
            val default = steepestZoom(plan(0.6, camera))
            val smooth = steepestZoom(plan(1.0, camera))
            assertTrue("$camera sharp $sharp default $default", default < sharp)
            assertTrue("$camera default $default smooth $smooth", smooth < default)
            assertTrue("$camera smooth $smooth", smooth < 6)
        }
    }

    @Test
    fun smootherSettingsReframeLessOften() {
        assertTrue(plan(1.0).sceneCount <= plan(0.6).sceneCount)
        assertTrue(plan(0.6).sceneCount <= plan(0.0).sceneCount)
        assertTrue(plan(0.6).sceneCount >= 3)
    }

    @Test
    fun cameraMovesWithoutKinks() {
        for (camera in listOf(CameraMode.FOLLOW, CameraMode.SHOTS)) {
            val plan = plan(0.6, camera)
            var zoomJerk = 0.0
            var panJerk = 0.0
            for (f in 2 until plan.frameCount) {
                val a = log2(plan.cameraWidth[f - 2])
                val b = log2(plan.cameraWidth[f - 1])
                val c = log2(plan.cameraWidth[f])
                zoomJerk = max(zoomJerk, abs(c - 2 * b + a) * plan.fps * plan.fps)
                // Pan acceleration in frame widths per second squared.
                val ax = (plan.cameraX[f] - 2 * plan.cameraX[f - 1] + plan.cameraX[f - 2]) / plan.cameraWidth[f]
                val ay = (plan.cameraY[f] - 2 * plan.cameraY[f - 1] + plan.cameraY[f - 2]) / plan.cameraWidth[f]
                panJerk = max(panJerk, hypot(ax, ay) * plan.fps * plan.fps)
            }
            assertTrue("$camera zoom acceleration $zoomJerk", zoomJerk < 12)
            assertTrue("$camera pan acceleration $panJerk", panJerk < 12)
        }
    }

    @Test
    fun walksTheRouteInOrderAndEndsOnTheOverview() {
        for (camera in CameraMode.entries) {
            val plan = plan(camera = camera)
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
    }

    @Test
    fun theFlightGetsAFairShareAndAWideView() {
        val plan = plan(camera = CameraMode.FOLLOW)
        val flight = (0 until plan.frameCount).filter { plan.headSegment[it] == 41 }
        val seconds = flight.size / plan.fps.toDouble()
        assertTrue("flight $seconds", seconds in 0.5..5.0)
        // Halfway across, the camera shows a good part of the 2,300 km.
        val middle = flight[flight.size / 2]
        val route = plan.route
        val span = hypot(route.x[42] - route.x[41], route.y[42] - route.y[41])
        assertTrue("width ${plan.cameraWidth[middle] / span}", plan.cameraWidth[middle] > span * 0.3)
    }

    @Test
    fun glidesAcrossGapsBetweenDays() {
        val minute = 60_000L
        val day = 86_400_000L
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val times = ArrayList<Long>()
        for (i in 0..20) {
            lats += 48.85 + 0.001 * i
            lons += 2.35
            times += i * 5 * minute
        }
        for (i in 0..20) {
            lats += 48.87 + 0.001 * i
            lons += 2.40
            times += 3 * day + i * 5 * minute
        }
        val route = route(lats, lons, times.toLongArray(), LongArray(lats.size), breaks = setOf(21))
        for (camera in CameraMode.entries) {
            val plan = plan(camera = camera, route = route)
            val gliding = (0 until plan.frameCount).filter { plan.gliding(it) }
            assertTrue("$camera glides ${gliding.size}", gliding.size >= 10)
            for (f in gliding) {
                assertEquals(20, plan.headSegment[f])
                assertEquals(route.meters[20], plan.headMeters[f], 0.0)
                assertTrue(plan.headTime[f] == route.times[20] || plan.headTime[f] == route.times[21])
            }
            assertFalse(plan.gliding(0))
        }
    }

    @Test
    fun paceSharesTimeByScreenDistance() {
        val route = trip()
        val n = route.size
        val screen = DoubleArray(n - 1) { 1.0 }
        screen[10] = 3.0
        val pace = Planner.pace(route, screen, DoubleArray(n - 1), DoubleArray(n), 12.0)
        assertEquals(12.0, pace.arrive[n - 1], 1e-9)
        for (i in 1 until n) assertTrue(pace.arrive[i] >= pace.depart[i - 1])
        val one = pace.arrive[2] - pace.depart[1]
        val three = pace.arrive[11] - pace.depart[10]
        assertEquals(3.0, three / one, 1e-6)
    }

    @Test
    fun paceGivesEveryGroupItsMinimum() {
        val route = trip()
        val n = route.size
        val screen = DoubleArray(n - 1) { if (it < 40) 1.0 else 0.001 }
        val group = IntArray(n - 1) { if (it < 40) 0 else 1 }
        val pace = Planner.pace(route, screen, DoubleArray(n - 1), DoubleArray(n), 10.0, group, doubleArrayOf(0.0, 2.0))
        assertEquals(2.0, pace.arrive[n - 1] - pace.depart[40], 1e-6)
        assertEquals(10.0, pace.arrive[n - 1], 1e-9)
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
        for (camera in CameraMode.entries) {
            val started = System.nanoTime()
            val plan = Planner.plan(route, MotionSettings(60.0, 60, 9.0 / 16, 0.6, 0.1, camera, pauseAtStops = true))
            val millis = (System.nanoTime() - started) / 1_000_000
            assertTrue("$camera took $millis ms", millis < 3_000)
            assertEquals(3_600, plan.frameCount)
        }
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
