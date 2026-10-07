package io.github.hansoda.trace.motion

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.route.Route
import kotlin.math.IEEErem
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** How the camera films the route. */
enum class CameraMode(val id: String) {
    /** Keeps the dot in the middle of the frame while the map moves under it. */
    TRACK("track"),

    /** Glides after the dot, looking a little ahead of it and rounding its corners. */
    FOLLOW("follow"),

    /** Keeps the dot low in the frame and turns the map so the way ahead points up. */
    HEADING("heading"),

    /** Holds a steady shot of each part of the trip and glides between them. */
    SHOTS("shots"),

    /** Shows the whole route all the time. */
    WHOLE("whole"),
    ;

    /** Travels with the dot, as close as [CameraDistance] says, zooming out for long trips. */
    val travels: Boolean get() = this == TRACK || this == FOLLOW || this == HEADING

    companion object {
        fun fromId(id: String?): CameraMode = entries.firstOrNull { it.id == id } ?: TRACK
    }
}

/** How much of the map a travelling camera shows around the dot. */
enum class CameraDistance(val id: String, internal val factor: Double) {
    CLOSE("close", 0.55),
    MEDIUM("medium", 1.0),
    FAR("far", 1.8),
    ;

    companion object {
        fun fromId(id: String?): CameraDistance = entries.firstOrNull { it.id == id } ?: MEDIUM
    }
}

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
    val camera: CameraMode = CameraMode.TRACK,
    /** Lets the dot wait a moment at long stops. Off, it never stops moving. */
    val pauseAtStops: Boolean = false,
    /** How close a travelling camera stays; the others frame whole parts of the trip. */
    val distance: CameraDistance = CameraDistance.MEDIUM,
    /** How far a lock-on or heading-up camera trails the dot before catching up, 0–1. */
    val lag: Double = 0.0,
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
    /** How far the map is turned clockwise around the frame's centre, in radians; 0 is north up. */
    val cameraAngle: DoubleArray,
    /** True when the map turns with the route. Place names are left off, so none stands on its head. */
    val turns: Boolean,
    val headX: DoubleArray,
    val headY: DoubleArray,
    /** The trail runs through route points 0..headSegment, then on to the head. */
    val headSegment: IntArray,
    val headMeters: DoubleArray,
    val headTime: LongArray,
    /** First frame after the dot arrives, when the camera eases out to the whole route. */
    val endingFrame: Int,
    /** Number of parts the camera frames separately. */
    val sceneCount: Int,
) {
    val durationSeconds: Double get() = frameCount / fps.toDouble()

    fun frameAt(seconds: Double): Int = (seconds * fps).roundToInt().coerceIn(0, frameCount - 1)

    /** True while the dot jumps between separate days; no line is drawn for the jump. */
    fun gliding(frame: Int): Boolean {
        val segment = headSegment[frame]
        return segment + 1 < route.size && route.breakBefore[segment + 1] &&
            (headX[frame] != route.x[segment] || headY[frame] != route.y[segment])
    }
}

/** A camera position: centre and visible width in world units. */
internal data class Framing(val x: Double, val y: Double, val width: Double)

/**
 * A stretch of route, `start..end`, that one shot can show: [framing] fits it. [jump] marks a
 * single long jump such as a flight, or the gap between days that weren't chosen.
 */
internal class Scene(val start: Int, val end: Int, val framing: Framing, val jump: Boolean)

/**
 * Plans the camera and the timing of a video. The route is cut into scenes at stops, at long
 * jumps such as flights and at gaps between chosen days; neighbouring scenes merge while none
 * of them has to zoom out by more than the smoothness allows. The dot moves at a steady pace
 * across the screen, so every scene reads at the same speed whatever its scale.
 *
 * - [CameraMode.TRACK] keeps the dot in the middle and zooms to the scale of the current scene.
 * - [CameraMode.FOLLOW] travels with the dot, looking a little ahead of it, and zooms the same.
 * - [CameraMode.HEADING] tracks the dot low in the frame and turns the map with its heading.
 * - [CameraMode.SHOTS] holds each scene's shot and eases to the next one as the dot crosses
 *   into it.
 * - [CameraMode.WHOLE] shows everything at once.
 *
 * The dot only waits when [MotionSettings.pauseAtStops] asks for it. Every video ends by easing
 * out to the whole route and holding it.
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

    /** Share of a scene's shot the follow camera shows, so it has to travel along. */
    private const val FOLLOW_ZOOM = 0.55

    /** Flights and jumps between days are shown nearly whole, start and end together. */
    private const val FOLLOW_ZOOM_JUMP = 0.9

    /** How far ahead of the dot the follow camera looks, as a share of the frame. */
    private const val LEAD = 0.16

    /** Seconds of video the follow camera looks ahead. */
    private const val LEAD_SECONDS = 0.8

    /** Rounds of settling the follow camera's zoom and the dot's pace on each other. */
    private const val FOLLOW_ROUNDS = 5

    /** Closest the dot may come to the frame's edge, as a share of the frame. */
    private const val EDGE = 0.05

    /** How far below the middle the heading camera keeps the dot, as a share of the frame's height. */
    private const val DROP = 0.15

    /** Fastest the heading camera turns the map, in radians a second. */
    private const val MAX_TURN = 100 * PI / 180

    /** Seconds over which the heading camera's turning is smoothed after the speed limit. */
    private const val TURN_EASING = 0.25

    /** Seconds a lock-on camera takes to catch up with the dot at full lag. */
    private const val MAX_LAG_SECONDS = 1.2

    /** Furthest a lagging camera lets the dot get from its place, as a share of the frame's short side. */
    private const val MAX_LAG = 0.25

    /** Seconds over which a lock-on camera without lag rounds the dot's corners. */
    private const val ROUNDING = 0.1

    fun plan(route: Route, settings: MotionSettings): Plan {
        require(route.size >= 2) { "A route needs at least two points" }
        val fps = settings.fps
        val smoothness = settings.smoothness.coerceIn(0.0, 1.0)
        val mode = settings.camera
        val inset = settings.topInset.coerceIn(0.0, 0.3)
        val aspect = settings.aspect
        val framer = Framer(aspect, inset)
        val frameCount = max(2, (settings.durationSeconds * fps).roundToInt())
        val total = frameCount / fps.toDouble()
        val n = route.size

        val scenes = scenes(route, framer, smoothness)
        val segmentScene = IntArray(n - 1)
        scenes.forEachIndexed { k, scene -> for (i in scene.start until scene.end) segmentScene[i] = k }
        val bounds = route.bounds()
        val overview = framer.frame(bounds.minX, bounds.minY, bounds.maxX, bounds.maxY, OVERVIEW_PADDING)

        // The journey, then easing out to the whole route, then holding it.
        val hold = (total * 0.08).coerceIn(0.8, 2.0)
        val available = max(0.5, total - hold)
        val outro = if (mode == CameraMode.WHOLE) 0.0 else min(transitionSeconds(scenes.last().framing, overview, smoothness), available * 0.25)
        val journey = available - outro

        val length = DoubleArray(n - 1) { hypot(route.x[it + 1] - route.x[it], route.y[it + 1] - route.y[it]) }
        // Flights and jumps between days take a set time, however far they go, while the
        // camera pulls out and dives back in.
        val glide = DoubleArray(n - 1)
        scenes.forEachIndexed { k, scene ->
            if (!scene.jump) return@forEachIndexed
            val before = if (k > 0) transitionSeconds(scenes[k - 1].framing, scene.framing, smoothness) else 0.0
            val after = if (k + 1 < scenes.size) transitionSeconds(scene.framing, scenes[k + 1].framing, smoothness) else 0.0
            glide[scene.start] = (0.6 + 0.5 * (before + after)).coerceIn(1.0, 3.5)
        }
        for (i in 0 until n - 1) {
            // A gap between days close enough to share a shot.
            if (route.breakBefore[i + 1] && glide[i] == 0.0) glide[i] = 0.6
        }
        val pause = DoubleArray(n)
        if (settings.pauseAtStops) {
            // The longest stops first, while they fit in a quarter of the journey.
            val boundary = HashMap<Int, Int>()
            for (k in 0 until scenes.size - 1) boundary[scenes[k].end] = k
            var budget = journey * 0.25
            for (at in (1 until n - 1).filter { route.dwellMs[it] >= STOP_MS }.sortedByDescending { route.dwellMs[it] }) {
                var wait = min(1.0, 0.35 + 0.2 * log2(1 + route.dwellMs[at] / 3_600_000.0))
                val k = boundary[at]
                // Shots change while the dot waits, like turning a page.
                if (mode == CameraMode.SHOTS && k != null) wait = max(wait, transitionSeconds(scenes[k].framing, scenes[k + 1].framing, smoothness))
                if (wait > budget) continue
                pause[at] = wait
                budget -= wait
            }
        }

        val film = Film(route, fps, frameCount, aspect, inset, journey)
        when (mode) {
            CameraMode.WHOLE -> {
                film.pace(pace(route, DoubleArray(n - 1) { length[it] / overview.width }, glide, pause, journey))
                for (f in 0 until frameCount) film.set(f, overview)
            }
            CameraMode.SHOTS -> {
                val minimum = DoubleArray(scenes.size) { k ->
                    val before = if (k > 0) transitionSeconds(scenes[k - 1].framing, scenes[k].framing, smoothness) else 0.0
                    val after = if (k + 1 < scenes.size) transitionSeconds(scenes[k].framing, scenes[k + 1].framing, smoothness) else 0.0
                    0.25 + (before + after) / 2
                }
                val screen = DoubleArray(n - 1) { length[it] / scenes[segmentScene[it]].framing.width }
                film.pace(pace(route, screen, glide, pause, journey, segmentScene, minimum))
                film.shots(scenes, segmentScene, smoothness, overview, outro)
                // Steady on screen through the camera's moves too, then frame again around that.
                film.pace(film.timing, film.cameraWidth.copyOf())
                film.shots(scenes, segmentScene, smoothness, overview, outro)
            }
            CameraMode.TRACK, CameraMode.FOLLOW, CameraMode.HEADING -> {
                val distance = settings.distance.factor
                val target = DoubleArray(scenes.size) { k ->
                    val scene = scenes[k]
                    val zoom = if (scene.jump) FOLLOW_ZOOM_JUMP else FOLLOW_ZOOM
                    (max(minViewWidth(scene.framing.y, aspect), scene.framing.width * zoom) * distance).coerceAtMost(MAX_VIEW_WIDTH)
                }
                // Every scene lasts long enough for the camera to reach its zoom.
                val shots = scenes.mapIndexed { k, scene -> Framing(scene.framing.x, scene.framing.y, target[k]) }
                val minimum = DoubleArray(scenes.size) { k ->
                    max(
                        if (k > 0) transitionSeconds(shots[k - 1], shots[k], smoothness) else 0.0,
                        if (k + 1 < scenes.size) transitionSeconds(shots[k], shots[k + 1], smoothness) else 0.0,
                    )
                }
                val zoomBlur = (0.45 + 1.1 * smoothness) * fps
                // The camera's width depends on where the dot is when, and the dot's pace on the
                // camera's width; a few rounds settle both.
                val width = DoubleArray(n - 1) { target[segmentScene[it]] }
                var widths = DoubleArray(0)
                for (round in 0 until FOLLOW_ROUNDS) {
                    film.pace(pace(route, DoubleArray(n - 1) { length[it] / width[it] }, glide, pause, journey, segmentScene, minimum))
                    widths = film.followWidths(target, segmentScene, zoomBlur)
                    if (round == FOLLOW_ROUNDS - 1) break
                    for (i in 0 until n - 1) {
                        if (glide[i] > 0 || length[i] == 0.0) continue
                        width[i] = sqrt(width[i] * film.averageWidth(i, widths))
                    }
                }
                film.pace(film.timing, widths)
                when (mode) {
                    CameraMode.FOLLOW -> film.follow(widths, (0.35 + 0.5 * smoothness) * fps, overview, outro)
                    else -> film.track(
                        widths, settings.lag.coerceIn(0.0, 1.0) * MAX_LAG_SECONDS, overview, outro,
                        turn = if (mode == CameraMode.HEADING) (0.6 + 1.2 * smoothness) * fps else 0.0,
                    )
                }
            }
        }
        return film.toPlan(scenes.size)
    }

    // region Scenes

    internal fun scenes(route: Route, framer: Framer, smoothness: Double): List<Scene> {
        val n = route.size
        val length = DoubleArray(n - 1) { hypot(route.x[it + 1] - route.x[it], route.y[it + 1] - route.y[it]) }

        // Cut at stops, around long jumps and around gaps between days.
        val cuts = sortedSetOf(0, n - 1)
        val jumps = HashSet<Int>()
        for (i in 1 until n - 1) if (route.dwellMs[i] >= STOP_MS) cuts += i
        for (i in 0 until n - 1) {
            val meters = length[i] / Geo.worldPerMeter((route.y[i] + route.y[i + 1]) / 2)
            val around = (if (i > 0) length[i - 1] else 0.0) + (if (i < n - 2) length[i + 1] else 0.0)
            if (route.breakBefore[i + 1] || (meters > 50_000 && length[i] > 10 * around)) {
                cuts += i
                cuts += i + 1
                jumps += i
            }
        }

        val parts = ArrayList<Part>()
        var previous = -1
        for (cut in cuts) {
            if (previous >= 0 && cut > previous) parts += Part.of(route, length, previous, cut, framer, previous + 1 == cut && previous in jumps)
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
        while (true) {
            val pair = queue.poll() ?: break
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
        return merged.map { Scene(it.start, it.end, framer.frame(it.minX, it.minY, it.maxX, it.maxY, PADDING), it.jump) }
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
        val jump: Boolean,
    ) {
        fun merge(next: Part) = Part(
            start, next.end, path + next.path,
            min(minX, next.minX), min(minY, next.minY), max(maxX, next.maxX), max(maxY, next.maxY),
            min(detail, next.detail), false,
        )

        companion object {
            fun of(route: Route, length: DoubleArray, start: Int, end: Int, framer: Framer, jump: Boolean): Part {
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
                val detail = if (path / width >= VISIBLE_MOVEMENT) width else Double.POSITIVE_INFINITY
                return Part(start, end, path, minX, minY, maxX, maxY, detail, jump)
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
     * Shares out [seconds] of animation. Each segment takes time in proportion to [screen], the
     * share of the frame it crosses, so the dot moves at a steady pace on screen. Jumps between
     * days take their [glide] time and the dot waits at points for their [pause].
     *
     * @param group and [minimum]: segments of each group together take at least its minimum.
     */
    internal fun pace(
        route: Route,
        screen: DoubleArray,
        glide: DoubleArray,
        pause: DoubleArray,
        seconds: Double,
        group: IntArray? = null,
        minimum: DoubleArray? = null,
    ): Pace {
        val n = route.size
        val waits = pause.copyOf()
        var waiting = waits.sum()
        if (waiting > seconds * 0.25) {
            val scale = seconds * 0.25 / waiting
            for (i in waits.indices) waits[i] *= scale
            waiting = seconds * 0.25
        }
        val glides = glide.copyOf()
        var gliding = glides.sum()
        val glideBudget = (seconds - waiting) * 0.35
        if (gliding > glideBudget) {
            val scale = glideBudget / gliding
            for (i in glides.indices) glides[i] *= scale
            gliding = glideBudget
        }
        val moving = seconds - waiting - gliding
        val move = DoubleArray(n - 1)
        val movable = (0 until n - 1).filter { glide[it] <= 0 }
        val demand = movable.sumOf { screen[it] }
        when {
            movable.isEmpty() -> Unit
            demand <= 1e-12 -> for (i in movable) move[i] = moving / movable.size
            group == null || minimum == null -> for (i in movable) move[i] = screen[i] / demand * moving
            else -> {
                val groups = minimum.size
                val groupDemand = DoubleArray(groups)
                val members = IntArray(groups)
                for (i in movable) {
                    groupDemand[group[i]] += screen[i]
                    members[group[i]]++
                }
                val floor = DoubleArray(groups) { if (members[it] > 0) minimum[it] else 0.0 }
                val time = shareWithMinimums(groupDemand, floor, moving)
                for (i in movable) {
                    val k = group[i]
                    move[i] = if (groupDemand[k] > 1e-12) time[k] * screen[i] / groupDemand[k] else time[k] / members[k]
                }
            }
        }
        val arrive = DoubleArray(n)
        val depart = DoubleArray(n)
        var t = 0.0
        for (i in 0 until n) {
            arrive[i] = t
            t += waits[i]
            depart[i] = t
            if (i < n - 1) t += if (glide[i] > 0) glides[i] else move[i]
        }
        // Absorb rounding so the dot lands exactly at the end.
        arrive[n - 1] = seconds
        depart[n - 1] = seconds
        for (i in n - 2 downTo 0) {
            if (depart[i] > seconds) depart[i] = seconds
            if (arrive[i] > depart[i]) arrive[i] = depart[i]
        }
        return Pace(arrive, depart)
    }

    /**
     * Splits [total] in proportion to [demand], giving every share at least its [floor]: the
     * shares above their floor all get the same time per unit of demand.
     */
    private fun shareWithMinimums(demand: DoubleArray, floor: DoubleArray, total: Double): DoubleArray {
        val floors = floor.sum()
        if (floors >= total) return DoubleArray(demand.size) { if (floors > 0) floor[it] * total / floors else 0.0 }
        val demandSum = demand.sum()
        if (demandSum <= 1e-12) return DoubleArray(demand.size) { floor[it] + (total - floors) / demand.size }
        var low = 0.0
        var high = total / demandSum
        repeat(60) {
            val rate = (low + high) / 2
            val used = demand.indices.sumOf { max(floor[it], demand[it] * rate) }
            if (used > total) high = rate else low = rate
        }
        val shares = DoubleArray(demand.size) { max(floor[it], demand[it] * low) }
        val scale = total / shares.sum()
        return DoubleArray(demand.size) { shares[it] * scale }
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

    /** The frames being planned: where the dot is, then where the camera looks. */
    private class Film(
        val route: Route,
        val fps: Int,
        val frameCount: Int,
        val aspect: Double,
        val inset: Double,
        val journey: Double,
    ) {
        val cameraX = DoubleArray(frameCount)
        val cameraY = DoubleArray(frameCount)
        val cameraWidth = DoubleArray(frameCount)
        val cameraAngle = DoubleArray(frameCount)
        var turns = false
        val headX = DoubleArray(frameCount)
        val headY = DoubleArray(frameCount)
        val headSegment = IntArray(frameCount)
        val headMeters = DoubleArray(frameCount)
        val headTime = LongArray(frameCount)

        /** The segment whose scene frames each frame: the one the dot is on or about to take. */
        val headScene = IntArray(frameCount)
        lateinit var timing: Pace
        val endFrame: Int = (journey * fps).roundToInt().coerceIn(0, frameCount - 1)

        fun frameOf(seconds: Double): Int = (seconds * fps).roundToInt().coerceIn(0, frameCount - 1)

        /**
         * Places the dot on every frame. Given the camera's [widths], the dot moves along each
         * segment at a steady pace on screen even while the camera zooms; otherwise at a steady
         * pace on the ground.
         */
        fun pace(pace: Pace, widths: DoubleArray? = null) {
            timing = pace
            val n = route.size
            // Area under the camera width up to a moment, to measure screen distance.
            val swept: (Double) -> Double = if (widths == null) {
                { t -> t }
            } else {
                val area = DoubleArray(frameCount + 1)
                for (f in 0 until frameCount) area[f + 1] = area[f] + widths[f]
                { t ->
                    val x = (t * fps).coerceIn(0.0, frameCount.toDouble())
                    val f = min(x.toInt(), frameCount - 1)
                    area[f] + widths[f] * (x - f)
                }
            }
            var i = 0
            for (f in 0 until frameCount) {
                val u = min(f / fps.toDouble(), journey)
                while (i < n - 1 && pace.arrive[i + 1] <= u) i++
                headSegment[f] = i
                headScene[f] = min(i, n - 2)
                if (i == n - 1 || u <= pace.depart[i]) {
                    headX[f] = route.x[i]
                    headY[f] = route.y[i]
                    headMeters[f] = route.meters[i]
                    val waited = pace.depart[i] - pace.arrive[i]
                    val progress = if (waited > 0) ((u - pace.arrive[i]) / waited).coerceIn(0.0, 1.0) else 0.0
                    val leave = leaveTime(i)
                    headTime[f] = route.times[i] + ((leave - route.times[i]) * progress).toLong()
                } else {
                    val start = swept(pace.depart[i])
                    val whole = swept(pace.arrive[i + 1]) - start
                    val progress = if (whole > 0) ((swept(u) - start) / whole).coerceIn(0.0, 1.0) else 0.0
                    headX[f] = lerp(route.x[i], route.x[i + 1], progress)
                    headY[f] = lerp(route.y[i], route.y[i + 1], progress)
                    headMeters[f] = lerp(route.meters[i], route.meters[i + 1], progress)
                    val leave = leaveTime(i)
                    headTime[f] = if (route.breakBefore[i + 1]) {
                        // Skipped days don't scroll past: the date changes halfway through the jump.
                        if (progress < 0.5) leave else route.times[i + 1]
                    } else {
                        leave + ((route.times[i + 1] - leave) * progress).toLong()
                    }
                }
            }
        }

        /** Average camera width while the dot crosses segment [i]. */
        fun averageWidth(i: Int, widths: DoubleArray): Double {
            val from = timing.depart[i] * fps
            val to = timing.arrive[i + 1] * fps
            val first = from.toInt().coerceIn(0, frameCount - 1)
            val last = to.toInt().coerceIn(first, frameCount - 1)
            var sum = 0.0
            for (f in first..last) sum += widths[f]
            return sum / (last - first + 1)
        }

        fun set(f: Int, framing: Framing) {
            cameraX[f] = framing.x
            cameraY[f] = framing.y
            cameraWidth[f] = framing.width
        }

        private fun framing(f: Int) = Framing(cameraX[f], cameraY[f], cameraWidth[f])

        // region Follow

        /** The follow camera's width on every frame: each scene's width, blurred over time. */
        fun followWidths(target: DoubleArray, segmentScene: IntArray, blur: Double): DoubleArray {
            val logWidth = DoubleArray(frameCount) { log2(target[segmentScene[headScene[min(it, endFrame)]]]) }
            val smooth = gaussian(logWidth, blur)
            return DoubleArray(frameCount) { 2.0.pow(smooth[it]) }
        }

        /** Travels with the dot, looking ahead of it, then eases out to [overview]. */
        fun follow(widths: DoubleArray, blur: Double, overview: Framing, outro: Double) {
            // The dot's path measured in frame widths, so a flight seen from far away counts
            // the same as a walk seen close up. Smoothing it rounds the corners the camera
            // takes instead of jerking at each one.
            val pathX = DoubleArray(frameCount)
            val pathY = DoubleArray(frameCount)
            for (f in 1 until frameCount) {
                pathX[f] = pathX[f - 1] + (headX[f] - headX[f - 1]) / widths[f - 1]
                pathY[f] = pathY[f - 1] + (headY[f] - headY[f - 1]) / widths[f - 1]
            }
            val ahead = (LEAD_SECONDS * fps).roundToInt()
            val leadX = DoubleArray(frameCount)
            val leadY = DoubleArray(frameCount)
            for (f in 0 until frameCount) {
                val g = min(f + ahead, frameCount - 1)
                var dx = pathX[g] - pathX[f]
                var dy = pathY[g] - pathY[f]
                // As shares of the frame's width and height.
                val reach = hypot(dx / LEAD, dy * aspect / LEAD)
                if (reach > 1) {
                    dx /= reach
                    dy /= reach
                }
                leadX[f] = dx
                leadY[f] = dy
            }
            val roundX = gaussian(pathX, blur)
            val roundY = gaussian(pathY, blur)
            val aheadX = gaussian(leadX, blur)
            val aheadY = gaussian(leadY, blur)
            for (f in 0 until frameCount) {
                val width = widths[f]
                cameraX[f] = headX[f] + (roundX[f] - pathX[f] + aheadX[f]) * width
                cameraY[f] = headY[f] + (roundY[f] - pathY[f] + aheadY[f]) * width - inset / 2 * width / aspect
                cameraWidth[f] = width
            }
            keepDotInFrame()
            ending(overview, outro, overlap = min(0.8, outro * 0.5))
        }

        // endregion

        // region Track

        /**
         * Keeps the dot in the middle of the frame. With [lag] seconds the camera trails it like
         * a game's camera and catches up as it slows; without, the dot's path is rounded only
         * enough that the map doesn't jerk at every corner. A [turn] above zero also turns the
         * map so the way ahead points up, averaging the heading over that many frames, and keeps
         * the dot low in the frame to show more of what's coming.
         */
        fun track(widths: DoubleArray, lag: Double, overview: Framing, outro: Double, turn: Double) {
            val overlap = min(0.8, outro * 0.5)
            // The dot's path measured in frame widths, so the camera keeps up the same at any zoom.
            val pathX = DoubleArray(frameCount)
            val pathY = DoubleArray(frameCount)
            for (f in 1 until frameCount) {
                pathX[f] = pathX[f - 1] + (headX[f] - headX[f - 1]) / widths[f - 1]
                pathY[f] = pathY[f - 1] + (headY[f] - headY[f - 1]) / widths[f - 1]
            }
            // How far the dot is ahead of its place in the frame, in frame widths.
            val aheadX = DoubleArray(frameCount)
            val aheadY = DoubleArray(frameCount)
            if (lag > 0) {
                chase(pathX, pathY, lag, aheadX, aheadY)
            } else {
                val roundX = gaussian(pathX, ROUNDING * fps)
                val roundY = gaussian(pathY, ROUNDING * fps)
                for (f in 0 until frameCount) {
                    aheadX[f] = pathX[f] - roundX[f]
                    aheadY[f] = pathY[f] - roundY[f]
                }
            }
            turns = turn > 0
            if (turns) {
                headings(widths, turn)
                straighten(journey - overlap)
            }
            val drop = if (turns) DROP else 0.0
            for (f in 0 until frameCount) {
                val width = widths[f]
                // Up on screen, as a direction on the map, and how far the middle of the frame
                // sits that way from the dot.
                val upX = -sin(cameraAngle[f])
                val upY = -cos(cameraAngle[f])
                val shift = (inset / 2 + drop) * width / aspect
                cameraX[f] = headX[f] - aheadX[f] * width + upX * shift
                cameraY[f] = headY[f] - aheadY[f] * width + upY * shift
                cameraWidth[f] = width
            }
            ending(overview, outro, overlap)
        }

        /**
         * Chases the dot's [pathX], [pathY] like a game camera: a critically damped spring that
         * takes about [lag] seconds to catch up, and that the dot drags along rather than
         * getting further than [MAX_LAG] of the frame ahead. Fills [aheadX], [aheadY] with how
         * far the dot is ahead of the camera.
         */
        private fun chase(pathX: DoubleArray, pathY: DoubleArray, lag: Double, aheadX: DoubleArray, aheadY: DoubleArray) {
            // A step of the usual smooth-damp spring: stable at any frame rate, no overshoot.
            val omega = 2 / lag
            val x = omega / fps
            val decay = 1 / (1 + x + 0.48 * x * x + 0.235 * x * x * x)
            val reach = MAX_LAG * min(1.0, 1 / aspect)
            // Up to here the dot moves freely; beyond, the limit eases in.
            val free = 0.6 * reach
            var cameraAtX = pathX[0]
            var cameraAtY = pathY[0]
            var speedX = 0.0
            var speedY = 0.0
            for (f in 0 until frameCount) {
                if (f > 0) {
                    val changeX = cameraAtX - pathX[f]
                    val changeY = cameraAtY - pathY[f]
                    val pullX = (speedX + omega * changeX) / fps
                    val pullY = (speedY + omega * changeY) / fps
                    speedX = (speedX - omega * pullX) * decay
                    speedY = (speedY - omega * pullY) * decay
                    cameraAtX = pathX[f] + (changeX + pullX) * decay
                    cameraAtY = pathY[f] + (changeY + pullY) * decay
                }
                var dx = pathX[f] - cameraAtX
                var dy = pathY[f] - cameraAtY
                val distance = hypot(dx, dy)
                if (distance > free) {
                    val eased = free + (reach - free) * tanh((distance - free) / (reach - free))
                    dx *= eased / distance
                    dy *= eased / distance
                    // The dot drags the camera along.
                    cameraAtX = pathX[f] - dx
                    cameraAtY = pathY[f] - dy
                }
                aheadX[f] = dx
                aheadY[f] = dy
            }
        }

        /**
         * Turns the map so the dot's heading, averaged over [blur] frames, points up, never
         * faster than [MAX_TURN].
         */
        private fun headings(widths: DoubleArray, blur: Double) {
            // Movement in frame widths, so the heading follows what the eye sees.
            val moveX = DoubleArray(frameCount)
            val moveY = DoubleArray(frameCount)
            for (f in 0 until frameCount - 1) {
                moveX[f] = (headX[f + 1] - headX[f]) / widths[f]
                moveY[f] = (headY[f + 1] - headY[f]) / widths[f]
            }
            val aimX = gaussian(moveX, blur)
            val aimY = gaussian(moveY, blur)
            val step = MAX_TURN / fps
            var previous = Double.NaN
            for (f in 0 until frameCount) {
                if (aimX[f] == 0.0 && aimY[f] == 0.0) {
                    // Standing still: keep facing the same way.
                    cameraAngle[f] = previous
                    continue
                }
                var angle = -PI / 2 - atan2(aimY[f], aimX[f])
                if (!previous.isNaN()) angle = previous + (angle - previous).IEEErem(2 * PI).coerceIn(-step, step)
                cameraAngle[f] = angle
                previous = angle
            }
            val first = cameraAngle.indexOfFirst { !it.isNaN() }
            if (first < 0) {
                cameraAngle.fill(0.0)
                return
            }
            for (f in 0 until first) cameraAngle[f] = cameraAngle[first]
            gaussian(cameraAngle, TURN_EASING * fps).copyInto(cameraAngle)
        }

        /**
         * Turns the map back to north up, the short way round, by [end] seconds, while the dot
         * still holds its place in the frame; the ending then pulls straight out.
         */
        private fun straighten(end: Double) {
            val last = frameOf(end)
            val whole = 2 * PI * round(cameraAngle[last] / (2 * PI))
            for (f in 0 until frameCount) cameraAngle[f] -= whole
            // About as quick as the heading may turn, and quicker for small turns.
            val seconds = min(0.4 + 1.9 * abs(cameraAngle[last]) / MAX_TURN, end)
            for (f in 0 until frameCount) {
                val t = f / fps.toDouble()
                when {
                    t >= end -> cameraAngle[f] = 0.0
                    t > end - seconds -> cameraAngle[f] *= 1 - smootherstep((t - end + seconds) / seconds)
                }
            }
        }

        // endregion

        // region Shots

        /** Holds each scene's shot; eases to the next as the dot crosses over, or while it waits. */
        fun shots(scenes: List<Scene>, segmentScene: IntArray, smoothness: Double, overview: Framing, outro: Double) {
            class Move(val from: Int, val start: Double, val end: Double)

            val moves = ArrayList<Move>()
            val sceneStart = DoubleArray(scenes.size) { timing.depart[scenes[it].start] }
            val sceneEnd = DoubleArray(scenes.size) { timing.arrive[scenes[it].end] }
            for (k in 0 until scenes.size - 1) {
                val at = scenes[k].end
                val seconds = transitionSeconds(scenes[k].framing, scenes[k + 1].framing, smoothness)
                if (seconds <= 0) continue
                val waited = timing.depart[at] - timing.arrive[at]
                val (start, end) = if (waited >= seconds * 0.5) {
                    timing.arrive[at] to timing.depart[at]
                } else {
                    val middle = (timing.arrive[at] + timing.depart[at]) / 2
                    max(middle - seconds / 2, (sceneStart[k] + sceneEnd[k]) / 2) to
                        min(middle + seconds / 2, (sceneStart[k + 1] + sceneEnd[k + 1]) / 2)
                }
                if (end > start) moves += Move(k, start, end)
            }
            var next = 0
            for (f in 0 until frameCount) {
                val t = min(f / fps.toDouble(), journey)
                while (next < moves.size && moves[next].end < t) next++
                val move = moves.getOrNull(next)
                val framing = if (move != null && t >= move.start) {
                    between(scenes[move.from].framing, scenes[move.from + 1].framing, (t - move.start) / (move.end - move.start))
                } else {
                    scenes[segmentScene[headScene[min(f, endFrame)]]].framing
                }
                set(f, framing)
            }
            keepDotInFrame()
            ending(overview, outro, overlap = 0.0)
        }

        /** Nudges the camera wherever the dot would come too close to the edge. */
        private fun keepDotInFrame() {
            for (f in 0..endFrame) {
                val width = cameraWidth[f]
                val height = width / aspect
                val halfX = width * (0.5 - EDGE)
                val dx = headX[f] - cameraX[f]
                if (dx > halfX) cameraX[f] += dx - halfX else if (dx < -halfX) cameraX[f] += dx + halfX
                val top = height * (0.5 - inset - EDGE)
                val bottom = height * (0.5 - EDGE)
                val dy = headY[f] - cameraY[f]
                if (dy > bottom) cameraY[f] += dy - bottom else if (dy < -top) cameraY[f] += dy + top
            }
        }

        // endregion

        /**
         * Eases from wherever the camera is to [overview], starting [overlap] seconds before
         * the dot arrives, then holds the overview.
         */
        fun ending(overview: Framing, outro: Double, overlap: Double) {
            val start = journey - overlap
            val length = outro + overlap
            if (length <= 0) {
                for (f in endFrame + 1 until frameCount) set(f, overview)
                return
            }
            for (f in 0 until frameCount) {
                val t = f / fps.toDouble()
                if (t <= start) continue
                val progress = (t - start) / length
                set(f, if (progress >= 1) overview else between(framing(f), overview, progress))
            }
        }

        fun toPlan(scenes: Int) = Plan(
            route, fps, frameCount, cameraX, cameraY, cameraWidth, cameraAngle, turns,
            headX, headY, headSegment, headMeters, headTime, endFrame, scenes,
        )

        private fun leaveTime(i: Int): Long {
            val leave = route.times[i] + route.dwellMs[i]
            return if (i + 1 < route.size) min(leave, route.times[i + 1]) else leave
        }
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
