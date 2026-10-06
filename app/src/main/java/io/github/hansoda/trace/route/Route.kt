package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToInt

/** Axis-aligned box in world coordinates. */
data class Bounds(val minX: Double, val minY: Double, val maxX: Double, val maxY: Double) {
    val width: Double get() = maxX - minX
    val height: Double get() = maxY - minY
    val centerX: Double get() = (minX + maxX) / 2
    val centerY: Double get() = (minY + maxY) / 2
}

/** The route a video animates: the chosen travel points, in time order. */
class Route(
    val x: DoubleArray,
    val y: DoubleArray,
    val times: LongArray,
    val offsets: ShortArray,
    /** Distance travelled when reaching each point, measured on the full-detail path. */
    val meters: DoubleArray,
    /** How long the trip paused at each point before moving on. */
    val dwellMs: LongArray,
    /** Travel points available in the date range, for the slider label. */
    val availablePoints: Int,
) {
    val size: Int get() = x.size
    val totalMeters: Double get() = if (size == 0) 0.0 else meters[size - 1]

    fun bounds(): Bounds {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until size) {
            if (x[i] < minX) minX = x[i]
            if (x[i] > maxX) maxX = x[i]
            if (y[i] < minY) minY = y[i]
            if (y[i] > maxY) maxY = y[i]
        }
        return Bounds(minX, minY, maxX, maxY)
    }
}

/**
 * Everything inside a date range, cleaned up and ranked once, so moving the "Travel points"
 * slider only has to pick the top-ranked points.
 */
class RangeData internal constructor(
    val x: DoubleArray,
    val y: DoubleArray,
    val times: LongArray,
    val offsets: ShortArray,
    val meters: DoubleArray,
    val dwellMs: LongArray,
    private val ranks: IntArray,
) {
    val size: Int get() = x.size
    val totalMeters: Double get() = if (size == 0) 0.0 else meters[size - 1]

    /** Pauses of at least a quarter of an hour. */
    val stops: Int get() = dwellMs.count { it >= 15 * 60_000L }

    fun select(count: Int): Route {
        val keep = BooleanArray(size) { ranks[it] < count }
        val kept = keep.count { it }
        val outX = DoubleArray(kept)
        val outY = DoubleArray(kept)
        val outTimes = LongArray(kept)
        val outOffsets = ShortArray(kept)
        val outMeters = DoubleArray(kept)
        val outDwell = LongArray(kept)
        // Position in the output of the last kept point at or before each input point.
        val keptBefore = IntArray(size)
        var k = -1
        for (i in 0 until size) {
            if (keep[i]) {
                k++
                outX[k] = x[i]
                outY[k] = y[i]
                outTimes[k] = times[i]
                outOffsets[k] = offsets[i]
                outMeters[k] = meters[i]
            }
            keptBefore[i] = k
        }
        // A pause belongs to wherever the simplified route passes closest to it.
        for (i in 0 until size) {
            val dwell = dwellMs[i]
            if (dwell <= 0) continue
            val before = keptBefore[i].coerceAtLeast(0)
            val after = (before + 1).coerceAtMost(kept - 1)
            val target = if (keep[i] || distanceSquared(outX[before], outY[before], x[i], y[i]) <=
                distanceSquared(outX[after], outY[after], x[i], y[i])
            ) before else after
            outDwell[target] += dwell
        }
        return Route(outX, outY, outTimes, outOffsets, outMeters, outDwell, size)
    }

    private fun distanceSquared(ax: Double, ay: Double, bx: Double, by: Double): Double =
        (ax - bx) * (ax - bx) + (ay - by) * (ay - by)
}

object RouteBuilder {
    /** Fixes closer than this to where the trip stopped count as standing still. */
    const val STILL_RADIUS_METERS = 35.0

    /** Shorter pauses are just traffic lights and slow GPS. */
    const val MIN_DWELL_MS = 10 * 60_000L

    fun build(timeline: Timeline, from: Long, to: Long): RangeData {
        val start = timeline.lowerBound(from)
        val end = timeline.lowerBound(to)
        val clean = Cleaner(end - start)
        val spikes = SpikeFilter(timeline, start, end)
        for (i in start until end) {
            if (!spikes.isSpike(i)) clean.add(timeline.lat(i), timeline.lon(i), timeline.times[i], timeline.offsets[i])
        }
        clean.finish()

        val n = clean.size
        val x = DoubleArray(n) { Geo.x(clean.lon[it]) }
        val y = DoubleArray(n) { Geo.y(clean.lat[it]) }
        // Keep x continuous across the antimeridian so a Tokyo–LA flight crosses the Pacific
        // instead of the whole map. Tiles wrap, so x may leave 0..1.
        for (i in 1 until n) {
            while (x[i] - x[i - 1] > 0.5) x[i] -= 1.0
            while (x[i] - x[i - 1] < -0.5) x[i] += 1.0
        }
        val meters = DoubleArray(n)
        val dwell = LongArray(n)
        for (i in 1 until n) {
            meters[i] = meters[i - 1] + Geo.haversineMeters(clean.lat[i - 1], clean.lon[i - 1], clean.lat[i], clean.lon[i])
            val sameSpot = clean.lat[i] == clean.lat[i - 1] && clean.lon[i] == clean.lon[i - 1]
            val paused = clean.time[i] - clean.time[i - 1]
            if (sameSpot && paused >= MIN_DWELL_MS) dwell[i - 1] = paused
        }
        return RangeData(
            x, y, clean.time.copyOf(n), clean.offset.copyOf(n), meters, dwell,
            visvalingamRanks(x, y),
        )
    }

    /** Drops lone fixes that jump far away and straight back, faster than anything travels. */
    private class SpikeFilter(private val timeline: Timeline, private val start: Int, private val end: Int) {
        private var lastKept = -1

        fun isSpike(i: Int): Boolean {
            val previous = lastKept
            if (previous >= 0 && i + 1 < end) {
                val next = i + 1
                val out = distance(previous, i)
                val back = distance(i, next)
                val direct = distance(previous, next)
                val outSpeed = out / seconds(previous, i)
                val backSpeed = back / seconds(i, next)
                if (minOf(out, back) > 300 && out + back > 3 * (direct + 50) && outSpeed > 50 && backSpeed > 50) return true
            }
            lastKept = i
            return false
        }

        private fun distance(a: Int, b: Int) =
            Geo.haversineMeters(timeline.lat(a), timeline.lon(a), timeline.lat(b), timeline.lon(b))

        private fun seconds(a: Int, b: Int) = ((timeline.times[b] - timeline.times[a]) / 1000.0).coerceAtLeast(1.0)

        init {
            require(start <= end)
        }
    }

    /**
     * Collapses GPS jitter while standing still into two fixes at the same spot: arriving and
     * leaving. Moving fixes closer together than [STILL_RADIUS_METERS] are dropped too.
     */
    private class Cleaner(capacity: Int) {
        val lat = DoubleArray(capacity * 2 + 2)
        val lon = DoubleArray(capacity * 2 + 2)
        val time = LongArray(capacity * 2 + 2)
        val offset = ShortArray(capacity * 2 + 2)
        var size = 0
            private set

        private var anchor = -1
        private var stillUntil = 0L
        private var stillOffset: Short = 0

        fun add(latitude: Double, longitude: Double, at: Long, utcOffset: Short) {
            if (anchor >= 0 && Geo.haversineMeters(lat[anchor], lon[anchor], latitude, longitude) < STILL_RADIUS_METERS) {
                stillUntil = at
                stillOffset = utcOffset
                return
            }
            finish()
            append(latitude, longitude, at, utcOffset)
            anchor = size - 1
            stillUntil = at
        }

        /** Closes a pause at the current anchor, if there is one. */
        fun finish() {
            if (anchor >= 0 && stillUntil > time[size - 1] && size - 1 == anchor) {
                append(lat[anchor], lon[anchor], stillUntil, stillOffset)
            }
        }

        private fun append(latitude: Double, longitude: Double, at: Long, utcOffset: Short) {
            lat[size] = latitude
            lon[size] = longitude
            time[size] = at
            offset[size] = utcOffset
            size++
        }
    }
}

/** Maps the "Travel points" slider (0–1) to a point count on a log scale. */
object PointBudget {
    const val MIN_POINTS = 20
    const val DEFAULT_FRACTION = 0.8f

    fun count(fraction: Float, available: Int): Int {
        if (available <= MIN_POINTS) return available
        val low = ln(MIN_POINTS.toDouble())
        val high = ln(available.toDouble())
        return exp(low + (high - low) * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(MIN_POINTS, available)
    }
}
