package io.github.hansoda.trace.motion

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.route.Route
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.sin
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
        distance: CameraDistance = CameraDistance.MEDIUM,
        lag: Double = 0.0,
    ) = Planner.plan(route, MotionSettings(20.0, 30, aspect, smoothness, inset, camera, pause, distance, lag))

    /** Where a map point shows on frame [f], as shares of the frame's width and height from its middle. */
    private fun onScreen(plan: Plan, f: Int, x: Double, y: Double, aspect: Double): Pair<Double, Double> {
        val width = plan.cameraWidth[f]
        val angle = plan.cameraAngle[f]
        val ox = x - plan.cameraX[f]
        val oy = y - plan.cameraY[f]
        return (cos(angle) * ox - sin(angle) * oy) / width to (sin(angle) * ox + cos(angle) * oy) / (width / aspect)
    }

    @Test
    fun keepsTheDotInFrame() {
        for (camera in CameraMode.entries) {
            for (smoothness in listOf(0.0, 0.3, 0.6, 1.0)) {
                for (aspect in listOf(9.0 / 16, 1.0, 16.0 / 9)) {
                    for (pause in listOf(false, true)) {
                        for (lag in listOf(0.0, 1.0)) {
                            val plan = plan(smoothness, camera, aspect, pause = pause, lag = lag)
                            for (f in 0 until plan.frameCount) {
                                val (dx, dy) = onScreen(plan, f, plan.headX[f], plan.headY[f], aspect)
                                val where = "$camera smoothness $smoothness aspect $aspect pause $pause lag $lag frame $f"
                                assertTrue("$where dx $dx", abs(dx) <= 0.5)
                                assertTrue("$where dy $dy", dy in -0.5 + 0.1..0.5)
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    fun lockOnKeepsTheDotInTheMiddle() {
        for (aspect in listOf(9.0 / 16, 16.0 / 9)) {
            val plan = plan(camera = CameraMode.TRACK, aspect = aspect)
            assertFalse(plan.turns)
            // Until the camera starts easing out to the whole route.
            for (f in 0..(plan.endingFrame - plan.fps)) {
                val (dx, dy) = onScreen(plan, f, plan.headX[f], plan.headY[f], aspect)
                // Below the middle by half the title's band, so it's centred in the map under it.
                assertTrue("frame $f dx $dx", abs(dx) <= 0.05)
                assertTrue("frame $f dy $dy", abs(dy - 0.05) <= 0.05)
            }
        }
    }

    @Test
    fun laggingCameraTrailsTheDotAndCatchesUp() {
        for (camera in listOf(CameraMode.TRACK, CameraMode.HEADING)) {
            val locked = plan(camera = camera)
            val lagging = plan(camera = camera, lag = 1.0)
            val home = if (camera == CameraMode.HEADING) 0.2 else 0.05
            var lead = 0.0
            var moving = 0
            var ahead = 0
            for (f in 1..(lagging.endingFrame - lagging.fps)) {
                val (dx, dy) = onScreen(lagging, f, lagging.headX[f], lagging.headY[f], 9.0 / 16)
                // Never more than a quarter of the frame's short side from where the dot belongs.
                val away = hypot(dx, (dy - home) * 16 / 9)
                assertTrue("$camera frame $f away $away", away <= 0.25 + 1e-9)
                lead += away
                // The dot runs ahead of the camera, the way it's going.
                val (x0, y0) = onScreen(lagging, f, lagging.headX[f - 1], lagging.headY[f - 1], 9.0 / 16)
                val (x1, y1) = onScreen(lagging, f, lagging.headX[f], lagging.headY[f], 9.0 / 16)
                if (hypot(x1 - x0, (y1 - y0) * 16 / 9) < 1e-3) continue
                moving++
                if ((x1 - x0) * dx + (y1 - y0) * (dy - home) * 256 / 81 > 0) ahead++
            }
            var lockedLead = 0.0
            for (f in 1..(locked.endingFrame - locked.fps)) {
                val (dx, dy) = onScreen(locked, f, locked.headX[f], locked.headY[f], 9.0 / 16)
                lockedLead += hypot(dx, (dy - home) * 16 / 9)
            }
            assertTrue("$camera lead $lead locked $lockedLead", lead > lockedLead * 3)
            assertTrue("$camera ahead on $ahead of $moving frames", ahead >= moving * 0.8)
            // It catches up once the dot arrives.
            val (dx, dy) = onScreen(lagging, lagging.endingFrame, lagging.headX[lagging.endingFrame], lagging.headY[lagging.endingFrame], 9.0 / 16)
            assertTrue("$camera ends dx $dx dy $dy", abs(dx) < 0.5 && abs(dy) < 0.5)
        }
    }

    @Test
    fun headingUpTurnsTheWayAheadUp() {
        val plan = plan(camera = CameraMode.HEADING)
        assertTrue(plan.turns)
        var turnedFastest = 0.0
        var ahead = 0
        var moving = 0
        for (f in 1..(plan.endingFrame - plan.fps)) {
            turnedFastest = max(turnedFastest, abs(plan.cameraAngle[f] - plan.cameraAngle[f - 1]) * plan.fps)
            // The dot sits low in the frame, with the way ahead above it.
            val (_, dy) = onScreen(plan, f, plan.headX[f], plan.headY[f], 9.0 / 16)
            assertTrue("frame $f dy $dy", dy in 0.15..0.3)
            // Which way the dot moves on screen.
            val (x0, y0) = onScreen(plan, f, plan.headX[f - 1], plan.headY[f - 1], 9.0 / 16)
            val (x1, y1) = onScreen(plan, f, plan.headX[f], plan.headY[f], 9.0 / 16)
            if (hypot(x1 - x0, y1 - y0) < 1e-4) continue
            moving++
            val offUp = abs(atan2(x1 - x0, -(y1 - y0)))
            if (offUp < PI / 4) ahead++
        }
        assertTrue("turned ${turnedFastest * 180 / PI}° a second", turnedFastest <= 100 * PI / 180 + 1e-9)
        assertTrue("up on $ahead of $moving frames", ahead >= moving * 0.75)
        // North is up again by the end.
        assertEquals(0.0, plan.cameraAngle[plan.frameCount - 1], 0.0)
        for (camera in CameraMode.entries.filter { it != CameraMode.HEADING }) {
            val upright = plan(camera = camera)
            assertFalse(upright.turns)
            assertTrue(upright.cameraAngle.all { it == 0.0 })
        }
    }

    @Test
    fun distanceSetsHowMuchTheCameraShows() {
        fun average(plan: Plan) = (0..plan.endingFrame).sumOf { plan.cameraWidth[it] } / (plan.endingFrame + 1)
        for (camera in CameraMode.entries.filter { it.travels }) {
            val close = average(plan(camera = camera, distance = CameraDistance.CLOSE))
            val medium = average(plan(camera = camera, distance = CameraDistance.MEDIUM))
            val far = average(plan(camera = camera, distance = CameraDistance.FAR))
            assertTrue("$camera close $close medium $medium", close < medium * 0.8)
            assertTrue("$camera medium $medium far $far", far > medium * 1.3)
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

    /** How far the dot moves across the screen, for each frame width it moves across the map. */
    private fun screenShare(plan: Plan): Double {
        var onScreen = 0.0
        var onMap = 0.0
        for (f in 1..plan.endingFrame) {
            val (x0, y0) = onScreen(plan, f - 1, plan.headX[f - 1], plan.headY[f - 1], 9.0 / 16)
            val (x1, y1) = onScreen(plan, f, plan.headX[f], plan.headY[f], 9.0 / 16)
            onScreen += hypot(x1 - x0, y1 - y0)
            onMap += hypot((plan.headX[f] - plan.headX[f - 1]) / plan.cameraWidth[f], (plan.headY[f] - plan.headY[f - 1]) / plan.cameraWidth[f] * 9 / 16)
        }
        return onScreen / onMap
    }

    @Test
    fun glideHoldsTheFrameWhileLockOnMovesTheMap() {
        // Gliding, the map mostly holds still and the dot crosses the screen; locked on, the
        // dot holds its place and the map moves.
        val gliding = screenShare(plan(camera = CameraMode.FOLLOW))
        val locked = screenShare(plan(camera = CameraMode.TRACK))
        assertTrue("gliding $gliding", gliding > 0.6)
        assertTrue("locked $locked", locked < 0.15)
        // The gliding camera keeps the dot well inside the frame, under the title.
        val plan = plan(camera = CameraMode.FOLLOW)
        for (f in 0..plan.endingFrame) {
            val (dx, dy) = onScreen(plan, f, plan.headX[f], plan.headY[f], 9.0 / 16)
            assertTrue("frame $f dx $dx", abs(dx) <= 0.45)
            assertTrue("frame $f dy $dy", dy in -0.35..0.45)
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
            // The last frames hold still, north up.
            assertEquals(plan.cameraWidth[last], plan.cameraWidth[last - 10], plan.cameraWidth[last] * 1e-9)
            assertEquals(0.0, plan.cameraAngle[last - 10], 0.0)
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

    /** Seconds the dot spends on route points [from]..[to]. */
    private fun secondsOn(plan: Plan, from: Int, to: Int): Double =
        (0..plan.endingFrame).count { plan.headSegment[it] in from until to } / plan.fps.toDouble()

    @Test
    fun gpsWobbleDoesNotSlowTheDot() {
        // Two walks the same length, the second with GPS wobbling 15 m either side of the way:
        // nearly three times as far, point to point.
        val minute = 60_000L
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val times = ArrayList<Long>()
        val dwell = ArrayList<Long>()
        for (i in 0..40) {
            lats += 48.85 + 0.0001 * i
            lons += 2.35
            times += i * minute
            dwell += if (i == 40) 30 * minute else 0
        }
        for (i in 0..40) {
            lats += 48.855 + 0.0001 * i
            lons += 2.35 + if (i % 2 == 0) 0.0002 else -0.0002
            times += (80 + i) * minute
            dwell += 0
        }
        val route = route(lats, lons, times.toLongArray(), dwell.toLongArray())
        for (camera in CameraMode.entries) {
            val plan = plan(camera = camera, route = route)
            val straight = secondsOn(plan, 0, 40)
            val wobbly = secondsOn(plan, 41, 81)
            assertTrue("$camera straight $straight wobbly $wobbly", wobbly in straight * 0.7..straight * 1.4)
        }
    }

    @Test
    fun shortWalksKeepThePace() {
        // A drive, a short walk between two stops, and another drive.
        val minute = 60_000L
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val times = ArrayList<Long>()
        val dwell = ArrayList<Long>()
        fun add(lat: Double, lon: Double, time: Long, wait: Long = 0) {
            lats += lat
            lons += lon
            times += time
            dwell += wait
        }
        for (i in 0..20) add(48.70 + 0.008 * i, 2.20, i * minute, if (i == 20) 20 * minute else 0)
        for (i in 1..6) add(48.86 + 0.0004 * i, 2.20 + 0.0003 * i, (40 + 2 * i) * minute, if (i == 6) 20 * minute else 0)
        for (i in 1..20) add(48.8624 + 0.008 * i, 2.2018, (80 + i) * minute)
        val route = route(lats, lons, times.toLongArray(), dwell.toLongArray())
        for (camera in listOf(CameraMode.TRACK, CameraMode.FOLLOW, CameraMode.HEADING)) {
            val plan = plan(camera = camera, route = route)
            // The dot's speed across the map, in frame widths a second.
            fun speed(f: Int) = hypot(plan.headX[f] - plan.headX[f - 1], plan.headY[f] - plan.headY[f - 1]) / plan.cameraWidth[f] * plan.fps
            val all = (1..plan.endingFrame).filter { plan.headSegment[it] < route.size - 1 }.map(::speed).sorted()
            val median = all[all.size / 2]
            val walk = (1..plan.endingFrame).filter { plan.headSegment[it] in 21 until 26 }.map(::speed)
            val walking = walk.average()
            assertTrue("$camera walking $walking median $median", walking > median * 0.6)
        }
    }

    @Test
    fun realSpeedTakesTheTimeTheTripTook() {
        // A 2 km walk that took half an hour, then a 2 km drive that took three minutes.
        val minute = 60_000L
        val lats = ArrayList<Double>()
        val lons = ArrayList<Double>()
        val times = ArrayList<Long>()
        for (i in 0..20) {
            lats += 48.85 + 0.0009 * i
            lons += 2.35
            times += i * 90_000L
        }
        for (i in 1..20) {
            lats += 48.868 + 0.0009 * i
            lons += 2.35
            times += 30 * minute + i * 9_000L
        }
        val route = route(lats, lons, times.toLongArray(), LongArray(lats.size))
        for (camera in CameraMode.entries) {
            val even = Planner.plan(route, MotionSettings(20.0, 30, 9.0 / 16, 0.6, 0.1, camera))
            val real = Planner.plan(route, MotionSettings(20.0, 30, 9.0 / 16, 0.6, 0.1, camera, speed = Speed.REAL))
            val evenShare = secondsOn(even, 0, 20) / secondsOn(even, 20, 40)
            val realShare = secondsOn(real, 0, 20) / secondsOn(real, 20, 40)
            assertTrue("$camera even $evenShare", evenShare in 0.6..1.7)
            assertTrue("$camera real $realShare", realShare in 6.0..14.0)
        }
    }

    @Test
    fun realSpeedSkipsTimeStandingStill() {
        // A phone left on a table for hours, its fixes a few metres apart, takes no longer
        // than walking past.
        val seconds = Planner.realSeconds(
            route(
                listOf(48.85, 48.85002, 48.86), listOf(2.35, 2.35, 2.35),
                longArrayOf(0, 3 * 3_600_000L, 3 * 3_600_000L + 15 * 60_000L), LongArray(3),
            ),
        )
        assertTrue("${seconds[0]}", seconds[0] < 10)
        assertEquals(15 * 60.0, seconds[1], 1.0)
    }

    @Test
    fun opensOnTheWholeRouteAndFliesIn() {
        val route = trip()
        val aspect = 9.0 / 16
        for (camera in listOf(CameraMode.TRACK, CameraMode.FOLLOW, CameraMode.HEADING, CameraMode.SHOTS)) {
            val plan = Planner.plan(route, MotionSettings(20.0, 30, aspect, 0.6, 0.1, camera, intro = true))
            // The first frame shows the whole trip, north up.
            assertEquals(0.0, plan.cameraAngle[0], 1e-9)
            for (i in 0 until route.size) {
                val (dx, dy) = onScreen(plan, 0, route.x[i], route.y[i], aspect)
                assertTrue("$camera point $i at $dx, $dy", abs(dx) <= 0.5 && abs(dy) <= 0.5)
            }
            // The dot waits at the start while the camera flies in, then sets off.
            val flown = (0 until plan.frameCount).first { plan.headX[it] != route.x[0] || plan.headY[it] != route.y[0] } - 1
            assertTrue("$camera sets off at $flown", flown >= plan.fps)
            // By then the camera is in close.
            assertTrue("$camera ${plan.cameraWidth[0] / plan.cameraWidth[flown]}", plan.cameraWidth[0] > 20 * plan.cameraWidth[flown])
            // The fly-in never jumps: each frame changes the view by a small step.
            for (f in 1..flown) {
                val zoom = abs(log2(plan.cameraWidth[f] / plan.cameraWidth[f - 1]))
                assertTrue("$camera frame $f zoom $zoom", zoom < 0.5)
            }
        }
        // Without it, the video starts close in.
        val plain = plan(camera = CameraMode.TRACK)
        assertTrue(plain.cameraWidth[0] < plain.cameraWidth.max() / 20)
    }

    @Test
    fun photosWaitWhereTheyWereTaken() {
        val route = trip()
        val inLisbon = route.times[10] + 30_000
        val inBerlin = route.times[60]
        val moments = listOf(
            Moment("lisbon", inLisbon, 2.0),
            Moment("berlin", inBerlin, 2.0, frames = 36, fps = 12.0),
            Moment("before", route.times.first() - 3 * 3_600_000L, 2.0),
        )
        val plan = Planner.plan(route, MotionSettings(20.0, 30, 9.0 / 16, 0.6, 0.1, CameraMode.TRACK, moments = moments))
        assertEquals(listOf("lisbon", "berlin"), plan.moments.map { it.moment.id })
        assertEquals(10, plan.moments[0].point)
        assertEquals(60, plan.moments[1].point)
        for (shown in plan.moments) {
            val seconds = (shown.endFrame - shown.startFrame) / plan.fps.toDouble()
            assertTrue("${shown.moment.id} $seconds", seconds in 1.8..2.2)
            // The dot waits at the photo's place while it's on screen.
            for (f in shown.startFrame..shown.endFrame) {
                assertEquals(route.x[shown.point], plan.headX[f], 1e-12)
                assertEquals(route.y[shown.point], plan.headY[f], 1e-12)
            }
        }
        // However many there are, they take at most half the journey and the dot still arrives.
        val many = List(60) { Moment("p$it", route.times[it % route.size], 2.0) }
        val crowded = Planner.plan(route, MotionSettings(20.0, 30, 9.0 / 16, 0.6, 0.1, CameraMode.TRACK, moments = many))
        val showing = crowded.moments.sumOf { it.endFrame - it.startFrame } / crowded.fps.toDouble()
        assertTrue("showing $showing", showing <= crowded.endingFrame / crowded.fps.toDouble() * 0.5 + 0.5)
        assertTrue(crowded.moments.size >= 5)
        assertEquals(route.size - 1, crowded.headSegment[crowded.frameCount - 1])
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
