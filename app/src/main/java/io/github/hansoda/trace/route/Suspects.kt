package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Geo
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Finds points that look like GPS errors: places the route visits only to rush straight back,
 * or reaches faster than any plane. The automatic clean-up already drops the most obvious
 * spikes; these are the doubtful ones, offered to the person to check.
 */
object Suspects {
    /** Detours shorter than this are GPS noise around a real place, not worth asking about. */
    private const val MIN_DETOUR_METERS = 150.0

    /** Slower out-and-back trips could be real errands. */
    private const val DETOUR_SPEED = 40.0

    /** Nothing on the ground or in the air gets anywhere this fast. */
    private const val IMPOSSIBLE_SPEED = 320.0

    /** @return point indices, the most suspicious first. Stops count once, at their arrival. */
    fun find(range: RangeData, limit: Int = 200): IntArray {
        val n = range.size
        val scores = ArrayList<Pair<Int, Double>>()
        var i = 0
        while (i < n) {
            // A stop is two points at the same spot: arriving and leaving.
            var last = i
            while (last + 1 < n && !range.breakBefore[last + 1] && range.x[last + 1] == range.x[i] && range.y[last + 1] == range.y[i]) last++
            val before = i - 1
            val after = last + 1
            val hasBefore = before >= 0 && !range.breakBefore[i]
            val hasAfter = after < n && !range.breakBefore[after]
            var score = 0.0
            if (hasBefore && hasAfter) {
                val out = meters(range, before, i)
                val back = meters(range, last, after)
                val direct = meters(range, before, after)
                val speed = max(out / seconds(range, before, i), back / seconds(range, last, after))
                val detour = (out + back) / (direct + 100.0)
                if (min(out, back) > MIN_DETOUR_METERS && detour > 2.5 && speed > DETOUR_SPEED) score = detour * speed / DETOUR_SPEED
            }
            if (hasBefore) {
                val speed = meters(range, before, i) / seconds(range, before, i)
                if (speed > IMPOSSIBLE_SPEED) score = max(score, speed / IMPOSSIBLE_SPEED * 10)
            }
            if (score > 0) scores += i to score
            i = last + 1
        }
        return scores.sortedByDescending { it.second }.take(limit).map { it.first }.toIntArray()
    }

    private fun meters(range: RangeData, a: Int, b: Int): Double =
        hypot(range.x[b] - range.x[a], range.y[b] - range.y[a]) / Geo.worldPerMeter((range.y[a] + range.y[b]) / 2)

    private fun seconds(range: RangeData, a: Int, b: Int): Double = ((range.times[b] - range.times[a]) / 1000.0).coerceAtLeast(1.0)
}
