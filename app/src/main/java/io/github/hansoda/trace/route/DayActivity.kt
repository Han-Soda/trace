package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline

/** How far the history moved on each day, for marking the calendar. */
class DayActivity(val firstDay: Long, private val meters: FloatArray) {
    val lastDay: Long get() = firstDay + meters.size - 1

    /** Metres travelled on [day], or -1 if there is no location history for it. */
    fun meters(day: Long): Float = if (day in firstDay..lastDay) meters[(day - firstDay).toInt()] else -1f

    fun hasData(day: Long): Boolean = meters(day) >= 0

    /** Days with real movement, not just GPS jitter at home. */
    fun moved(day: Long): Boolean = meters(day) >= MOVED_METERS

    companion object {
        const val MOVED_METERS = 500f

        /**
         * @param dayStart start of a local day in epoch milliseconds.
         * @param dayOf the local day of an epoch millisecond.
         */
        fun of(timeline: Timeline, dayStart: (Long) -> Long, dayOf: (Long) -> Long): DayActivity? {
            if (timeline.isEmpty()) return null
            val first = dayOf(timeline.times[0])
            val last = dayOf(timeline.times[timeline.size - 1])
            val days = (last - first + 1).toInt()
            if (days <= 0 || days > 100_000) return null
            val meters = FloatArray(days) { -1f }
            var end = timeline.lowerBound(dayStart(first))
            for (k in 0 until days) {
                val start = end
                end = timeline.lowerBound(dayStart(first + k + 1))
                if (end <= start) continue
                var total = 0.0
                for (i in start + 1 until end) {
                    total += Geo.haversineMeters(timeline.lat(i - 1), timeline.lon(i - 1), timeline.lat(i), timeline.lon(i))
                }
                meters[k] = total.toFloat()
            }
            return DayActivity(first, meters)
        }
    }
}
