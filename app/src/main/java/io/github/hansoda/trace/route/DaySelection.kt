package io.github.hansoda.trace.route

/**
 * The days a video covers: sorted, separate ranges of local epoch days, both ends included.
 * Neighbouring days always share a range, so ranges are at least a day apart and each gap
 * between them is a jump in the video.
 *
 * The video can also start part way through the first day and end part way through the last:
 * at [startMinute] and [endMinute] past midnight. Whole days run from 0 to [DAY_MINUTES].
 */
class DaySelection private constructor(
    private val starts: LongArray,
    private val ends: LongArray,
    val startMinute: Int = 0,
    val endMinute: Int = DAY_MINUTES,
) {
    val rangeCount: Int get() = starts.size
    val first: Long get() = starts[0]
    val last: Long get() = ends[ends.size - 1]
    val isRange: Boolean get() = starts.size == 1
    val dayCount: Int get() = starts.indices.sumOf { (ends[it] - starts[it] + 1).toInt() }

    /** True when the video starts or ends at a time of day rather than with whole days. */
    val hasTimes: Boolean get() = startMinute != 0 || endMinute != DAY_MINUTES

    /** The same days starting and ending at these times; null if the end wouldn't be after the start. */
    fun withTimes(startMinute: Int, endMinute: Int): DaySelection? {
        if (startMinute !in 0 until DAY_MINUTES || endMinute !in 1..DAY_MINUTES) return null
        if (first == last && endMinute <= startMinute) return null
        return DaySelection(starts, ends, startMinute, endMinute)
    }

    fun wholeDays(): DaySelection = if (hasTimes) DaySelection(starts, ends) else this

    /**
     * The chosen time as stretches of UTC milliseconds, one per range: [dayStart] is when a day
     * starts, and [timeOn] when a time of day is on a day, for the first range's start and the
     * last one's end when the selection has times.
     */
    fun spans(dayStart: (day: Long) -> Long, timeOn: (day: Long, minute: Int) -> Long): List<LongRange> =
        starts.indices.map { k ->
            val start = if (k == 0 && hasTimes) timeOn(starts[k], startMinute) else dayStart(starts[k])
            val end = if (k == starts.size - 1 && hasTimes) timeOn(ends[k], endMinute) else dayStart(ends[k] + 1)
            start until end
        }

    fun start(range: Int): Long = starts[range]
    fun end(range: Int): Long = ends[range]

    operator fun contains(day: Long): Boolean {
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= day) low = mid + 1 else high = mid
        }
        return low > 0 && day <= ends[low - 1]
    }

    /** The same days and times, [days] later or, when negative, earlier. */
    fun shifted(days: Long): DaySelection =
        DaySelection(LongArray(starts.size) { starts[it] + days }, LongArray(ends.size) { ends[it] + days }, startMinute, endMinute)

    /** Adds or removes one day, as whole days; null if that would leave nothing selected. */
    fun toggled(day: Long): DaySelection? {
        val days = days().toMutableSet()
        if (!days.add(day)) days.remove(day)
        return of(days)
    }

    fun days(): LongArray {
        val out = LongArray(dayCount)
        var k = 0
        for (r in starts.indices) for (day in starts[r]..ends[r]) out[k++] = day
        return out
    }

    /** "20000-20006,20010-20010", or with times "20000-20006@570-1080" */
    fun encode(): String =
        starts.indices.joinToString(",") { "${starts[it]}-${ends[it]}" } + if (hasTimes) "@$startMinute-$endMinute" else ""

    override fun equals(other: Any?): Boolean =
        other is DaySelection && starts.contentEquals(other.starts) && ends.contentEquals(other.ends) &&
            startMinute == other.startMinute && endMinute == other.endMinute

    override fun hashCode(): Int = (31 * starts.contentHashCode() + ends.contentHashCode()) * 31 * 31 + startMinute * 31 + endMinute

    override fun toString(): String = "DaySelection(${encode()})"

    companion object {
        const val DAY_MINUTES = 24 * 60

        fun range(from: Long, to: Long): DaySelection =
            DaySelection(longArrayOf(minOf(from, to)), longArrayOf(maxOf(from, to)))

        /** Groups single days into ranges; null if there are none. */
        fun of(days: Collection<Long>): DaySelection? = ofRanges(days.map { it to it })

        /** Sorts ranges and joins any that overlap or touch; null if there are none. */
        fun ofRanges(ranges: Collection<Pair<Long, Long>>): DaySelection? {
            if (ranges.isEmpty()) return null
            val sorted = ranges.map { (a, b) -> minOf(a, b) to maxOf(a, b) }.sortedBy { it.first }
            val starts = ArrayList<Long>(sorted.size)
            val ends = ArrayList<Long>(sorted.size)
            for ((start, end) in sorted) {
                if (ends.isNotEmpty() && start <= ends.last() + 1) {
                    ends[ends.size - 1] = maxOf(ends.last(), end)
                } else {
                    starts += start
                    ends += end
                }
            }
            return DaySelection(starts.toLongArray(), ends.toLongArray())
        }

        fun decode(text: String?): DaySelection? {
            if (text.isNullOrBlank()) return null
            val (days, times) = text.split('@', limit = 2).let { it[0] to it.getOrNull(1) }
            val ranges = days.split(',').map { part ->
                val bounds = part.split('-', limit = 2)
                val start = bounds.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
                val end = bounds.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
                start to end
            }
            val selection = ofRanges(ranges) ?: return null
            if (times == null) return selection
            val minutes = times.split('-', limit = 2).map { it.trim().toIntOrNull() }
            val start = minutes.getOrNull(0) ?: return selection
            val end = minutes.getOrNull(1) ?: return selection
            return selection.withTimes(start, end) ?: selection
        }
    }
}
