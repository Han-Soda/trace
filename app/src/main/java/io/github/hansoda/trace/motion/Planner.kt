package io.github.hansoda.trace.motion

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.route.Route
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/** What shapes the timing and camera of a video. */
data class MotionSettings(
    val durationSeconds: Double,
    val fps: Int,
    /** Frame width divided by height. */
    val aspect: Double,
    /** The "Zoom smoothness" slider, 0–1. */
    val smoothness: Double,
    /** Share of the frame height at the top kept clear for the title. */
    val topInset: Double = 0.0,
)

/** Where the camera looks and where the moving dot is on every frame of a video. */
class Plan(
    val route: Route,
    val fps: Int,
    val frameCount: Int,
    /** Camera centre in world coordinates. */
    val cameraX: DoubleArray,
    val cameraY: DoubleArray,
    /** Visible width in world units. */
    val cameraWidth: DoubleArray,
    val headX: DoubleArray,
    val headY: DoubleArray,
    /** The trail runs through route points 0..headSegment, then on to the head. */
    val headSegment: IntArray,
    val headMeters: DoubleArray,
    val headTime: LongArray,
    /** First frame of the closing move to the whole route. */
    val endingFrame: Int,
    /** Number of times the camera reframes during the journey. */
    val sceneCount: Int,
) {
    val durationSeconds: Double get() = frameCount / fps.toDouble()

    fun frameAt(seconds: Double): Int = (seconds * fps).roundToInt().coerceIn(0, frameCount - 1)
}

/** A camera position: centre and visible width in world units. */
internal data class Framing(val x: Double, val y: Double, val width: Double)

/** A stretch of route, `start..end`, filmed from one steady [framing]. */
internal class Scene(val start: Int, val end: Int, val framing: Framing)

/**
 * Plans the camera as a sequence of steady shots. The route is cut into scenes at stops and
 * at long jumps such as flights; neighbouring scenes merge while none of them has to zoom out
 * by more than the smoothness allows. Within a scene the camera holds still; between scenes the dot waits while the
 * camera eases to the next shot. The "Zoom smoothness" slider merges more scenes and slows
 * the moves between them.
 */
object Planner {
    /** The camera never shows less ground than this across the frame's short side. */
    const val MIN_VIEW_METERS = 1500.0

    private const val PADDING = 0.12
    private const val OVERVIEW_PADDING = 0.1
    private const val MAX_VIEW_WIDTH = 1.5

    /** Parts moving less than this share of their own shot are small detours that never block a merge. */
    private const val VISIBLE_MOVEMENT = 0.3

    /** Pauses shorter than this are traffic lights, not stops. */
    private const val STOP_MS = 10 * 60_000L

    fun plan(route: Route, settings: MotionSettings): Plan {
        require(route.size >= 2) { "A route needs at least two points" }
        val fps = settings.fps
        val smoothness = settings.smoothness.coerceIn(0.0, 1.0)
        val framer = Framer(settings.aspect, settings.topInset.coerceIn(0.0, 0.3))
        val frameCount = max(2, (settings.durationSeconds * fps).roundToInt())
        val total = frameCount / fps.toDouble()

        val scenes = scenes(route, framer, smoothness)
        val bounds = route.bounds()
        val overview = framer.frame(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY, OVERVIEW_PADDING)
        val intro = min(0.5, total * 0.04)
        val hold = (total * 0.1).coerceIn(1.0, 2.5)
        val available = max(0.5, total - intro - hold)
        val outro = min(transitionSeconds(scenes.last().framing, overview, smoothness), available * 0.3)
        val journey = available - outro
        val pace = pace(route, scenes, journey, smoothness)

        val n = route.size
        val segmentScene = IntArray(n - 1)
        val boundaryScene = IntArray(n) { -1 }
        scenes.forEachIndexed { k, scene ->
            for (i in scene.start until scene.end) segmentScene[i] = k
            if (k < scenes.size - 1) boundaryScene[scene.end] = k
        }

        val cameraX = DoubleArray(frameCount)
        val cameraY = DoubleArray(frameCount)
        val cameraWidth = DoubleArray(frameCount)
        val headX = DoubleArray(frameCount)
        val headY = DoubleArray(frameCount)
        val headSegment = IntArray(frameCount)
        val headMeters = DoubleArray(frameCount)
        val headTime = LongArray(frameCount)
        val endingStart = intro + journey
        val endingFrame = (endingStart * fps).roundToInt().coerceIn(0, frameCount - 1)
        var i = 0
        for (f in 0 until frameCount) {
            val t = f / fps.toDouble()
            val u = (t - intro).coerceIn(0.0, journey)
            while (i < n - 1 && pace.arrive[i + 1] <= u) i++
            headSegment[f] = i
            val framing: Framing
            if (i == n - 1 || u <= pace.depart[i]) {
                // Waiting at point i, possibly while the camera moves to the next scene.
                val waited = pace.depart[i] - pace.arrive[i]
                val progress = if (waited > 0) ((u - pace.arrive[i]) / waited).coerceIn(0.0, 1.0) else 0.0
                headX[f] = route.x[i]
                headY[f] = route.y[i]
                headMeters[f] = route.meters[i]
                val leave = leaveTime(route, i)
                headTime[f] = route.times[i] + ((leave - route.times[i]) * progress).toLong()
                val k = boundaryScene[i]
                framing = when {
                    k >= 0 -> between(scenes[k].framing, scenes[k + 1].framing, progress)
                    i == n - 1 -> scenes.last().framing
                    else -> scenes[segmentScene[i]].framing
                }
            } else {
                val progress = (u - pace.depart[i]) / (pace.arrive[i + 1] - pace.depart[i])
                headX[f] = lerp(route.x[i], route.x[i + 1], progress)
                headY[f] = lerp(route.y[i], route.y[i + 1], progress)
                headMeters[f] = lerp(route.meters[i], route.meters[i + 1], progress)
                val leave = leaveTime(route, i)
                headTime[f] = leave + ((route.times[i + 1] - leave) * progress).toLong()
                framing = scenes[segmentScene[i]].framing
            }
            val final = if (t >= endingStart && outro > 0) between(framing, overview, (t - endingStart) / outro) else framing
            cameraX[f] = final.x
            cameraY[f] = final.y
            cameraWidth[f] = final.width
        }
        return Plan(
            route, fps, frameCount, cameraX, cameraY, cameraWidth,
            headX, headY, headSegment, headMeters, headTime, endingFrame, scenes.size,
        )
    }

    // region Scenes

    internal fun scenes(route: Route, framer: Framer, smoothness: Double): List<Scene> {
        val n = route.size
        val length = DoubleArray(n - 1) { hypot(route.x[it + 1] - route.x[it], route.y[it + 1] - route.y[it]) }

        // Cut at stops and around long jumps.
        val cuts = sortedSetOf(0, n - 1)
        for (i in 1 until n - 1) if (route.dwellMs[i] >= STOP_MS) cuts += i
        for (i in 0 until n - 1) {
            val meters = length[i] / Geo.worldPerMeter((route.y[i] + route.y[i + 1]) / 2)
            val around = (if (i > 0) length[i - 1] else 0.0) + (if (i < n - 2) length[i + 1] else 0.0)
            if (meters > 50_000 && length[i] > 10 * around) {
                cuts += i
                cuts += i + 1
            }
        }

        val parts = ArrayList<Part>()
        var previous = -1
        for (cut in cuts) {
            if (previous >= 0 && cut > previous) parts += Part.of(route, length, previous, cut, framer)
            previous = cut
        }

        // Merge neighbours, cheapest first, while no part with visible movement would have to
        // zoom out by more than the smoothness allows. A queue of neighbour pairs keeps this
        // fast for a year of stops; pairs whose parts changed since are skipped.
        val tolerance = 0.25 + 2.5 * smoothness
        val count = parts.size
        val previousPart = IntArray(count) { it - 1 }
        val nextPart = IntArray(count) { if (it + 1 < count) it + 1 else -1 }
        val version = IntArray(count)
        val alive = BooleanArray(count) { true }
        val queue = java.util.PriorityQueue<Candidate>(maxOf(1, count), compareBy { it.loss })
        fun offer(left: Int) {
            val right = nextPart[left]
            if (right < 0) return
            val loss = mergeLoss(parts[left], parts[right], framer)
            if (loss <= tolerance) queue += Candidate(loss, left, version[left], right, version[right])
        }
        for (k in 0 until count - 1) offer(k)
        while (queue.isNotEmpty()) {
            val pair = queue.poll()
            if (!alive[pair.left] || !alive[pair.right] || version[pair.left] != pair.leftVersion ||
                version[pair.right] != pair.rightVersion
            ) continue
            parts[pair.left] = parts[pair.left].merge(parts[pair.right])
            alive[pair.right] = false
            version[pair.left]++
            val after = nextPart[pair.right]
            nextPart[pair.left] = after
            if (after >= 0) previousPart[after] = pair.left
            if (previousPart[pair.left] >= 0) offer(previousPart[pair.left])
            offer(pair.left)
        }
        val merged = parts.filterIndexed { index, _ -> alive[index] }
        return merged.map { Scene(it.start, it.end, framer.frame(it.minX, it.minY, it.maxX, it.maxY, PADDING)) }
    }

    /**
     * Zoom levels the most zoomed-in original part with visible movement would lose if [a] and
     * [b] shared a shot. Measuring against the original parts keeps chains of small merges
     * from drifting far out.
     */
    private fun mergeLoss(a: Part, b: Part, framer: Framer): Double {
        val detail = min(a.detail, b.detail)
        if (detail == Double.POSITIVE_INFINITY) return 0.0
        val merged = framer.width(min(a.minX, b.minX), min(a.minY, b.minY), max(a.maxX, b.maxX), max(a.maxY, b.maxY), PADDING)
        return max(0.0, log2(merged / detail))
    }

    private class Candidate(val loss: Double, val left: Int, val leftVersion: Int, val right: Int, val rightVersion: Int)

    /**
     * Route points `start..end` with their bounding box and path length. [detail] is the shot
     * width of the most zoomed-in original part inside with visible movement, or infinity.
     */
    private class Part(
        val start: Int, val end: Int, val path: Double,
        val minX: Double, val minY: Double, val maxX: Double, val maxY: Double,
        val detail: Double,
    ) {
        fun merge(next: Part) = Part(
            start, next.end, path + next.path,
            min(minX, next.minX), min(minY, next.minY), max(maxX, next.maxX), max(maxY, next.maxY),
            min(detail, next.detail),
        )

        companion object {
            fun of(route: Route, length: DoubleArray, start: Int, end: Int, framer: Framer): Part {
                var path = 0.0
                var minX = Double.POSITIVE_INFINITY
                var minY = Double.POSITIVE_INFINITY
                var maxX = Double.NEGATIVE_INFINITY
                var maxY = Double.NEGATIVE_INFINITY
                for (i in start..end) {
                    if (i < end) path += length[i]
                    minX = min(minX, route.x[i])
                    maxX = max(maxX, route.x[i])
                    minY = min(minY, route.y[i])
                    maxY = max(maxY, route.y[i])
                }
                val width = framer.width(minX, minY, maxX, maxY, PADDING)
                return Part(start, end, path, minX, minY, maxX, maxY, if (path / width >= VISIBLE_MOVEMENT) width else Double.POSITIVE_INFINITY)
            }
        }
    }

    /** Fits boxes into the frame, leaving room for the title at the top. */
    internal class Framer(private val aspect: Double, private val inset: Double) {
        fun width(minX: Double, minY: Double, maxX: Double, maxY: Double, padding: Double): Double = max(
            max((maxX - minX) / (1 - 2 * padding), (maxY - minY) * aspect / (1 - inset - 2 * padding)),
            minViewWidth((minY + maxY) / 2, aspect),
        ).coerceAtMost(MAX_VIEW_WIDTH)

        fun frame(minX: Double, minY: Double, maxX: Double, maxY: Double, padding: Double): Framing {
            val width = width(minX, minY, maxX, maxY, padding)
            return Framing((minX + maxX) / 2, (minY + maxY) / 2 - inset / 2 * width / aspect, width)
        }
    }

    internal fun minViewWidth(y: Double, aspect: Double): Double =
        MIN_VIEW_METERS * max(1.0, aspect) * Geo.worldPerMeter(y)

    // endregion

    // region Timing

    /** Arrival and departure times of each point, in seconds of journey animation. */
    internal class Pace(val arrive: DoubleArray, val depart: DoubleArray)

    /**
     * Moves the dot at a steady speed across the screen, so every scene reads at the same
     * pace whatever its scale. The dot waits at long stops and while the camera changes scene.
     */
    internal fun pace(route: Route, scenes: List<Scene>, seconds: Double, smoothness: Double): Pace {
        val n = route.size
        val move = DoubleArray(n - 1)
        val pause = DoubleArray(n)
        for (scene in scenes) {
            for (i in scene.start until scene.end) {
                move[i] = hypot(route.x[i + 1] - route.x[i], route.y[i + 1] - route.y[i]) / scene.framing.width
            }
        }
        for (i in 1 until n - 1) {
            if (route.dwellMs[i] >= STOP_MS) pause[i] = min(0.6, 0.1 * log2(1 + route.dwellMs[i] / 3_600_000.0))
        }
        for (k in 0 until scenes.size - 1) {
            val at = scenes[k].end
            pause[at] = max(pause[at], transitionSeconds(scenes[k].framing, scenes[k + 1].framing, smoothness))
        }
        var pausing = pause.sum()
        if (pausing > seconds * 0.45) {
            val scale = seconds * 0.45 / pausing
            for (i in pause.indices) pause[i] *= scale
            pausing = seconds * 0.45
        }
        val moving = move.sum()
        val movingTime = seconds - pausing
        val arrive = DoubleArray(n)
        val depart = DoubleArray(n)
        var t = 0.0
        for (i in 0 until n) {
            arrive[i] = t
            t += pause[i]
            depart[i] = t
            if (i < n - 1) t += if (moving > 1e-12) move[i] / moving * movingTime else movingTime / (n - 1)
        }
        // Absorb rounding so the dot lands exactly at the end.
        arrive[n - 1] = seconds
        depart[n - 1] = seconds
        return Pace(arrive, depart)
    }

    /** How long the camera takes to move between two shots, from the zoom and pan involved. */
    internal fun transitionSeconds(from: Framing, to: Framing, smoothness: Double): Double {
        val zoom = abs(log2(to.width / from.width))
        val pan = hypot(to.x - from.x, to.y - from.y)
        if (zoom < 0.01 && pan < 0.01 * min(from.width, to.width)) return 0.0
        val effort = zoom + 2 * lift(from, to)
        return (0.45 + (0.16 + 0.24 * smoothness) * effort).coerceIn(0.4, 4.0)
    }

    /** Extra zoom-out, in levels, for moves that pan further than they zoom. */
    private fun lift(from: Framing, to: Framing): Double {
        val zoom = abs(log2(to.width / from.width))
        val pan = hypot(to.x - from.x, to.y - from.y) / max(from.width, to.width)
        return max(0.0, log2(1 + pan) - zoom) * 0.5
    }

    /**
     * Eases between two shots. Zoom moves evenly in log space and the pan keeps pace with it,
     * so anything both shots show stays in frame throughout.
     */
    internal fun between(from: Framing, to: Framing, progress: Double): Framing {
        val e = smootherstep(progress)
        val base = 2.0.pow(lerp(log2(from.width), log2(to.width), e))
        val panned = if (abs(to.width - from.width) > from.width * 1e-6) {
            ((base - from.width) / (to.width - from.width)).coerceIn(0.0, 1.0)
        } else {
            e
        }
        val width = base * 2.0.pow(lift(from, to) * sin(PI * e))
        return Framing(lerp(from.x, to.x, panned), lerp(from.y, to.y, panned), width)
    }

    // endregion

    private fun leaveTime(route: Route, i: Int): Long {
        val leave = route.times[i] + route.dwellMs[i]
        return if (i + 1 < route.size) min(leave, route.times[i + 1]) else leave
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
