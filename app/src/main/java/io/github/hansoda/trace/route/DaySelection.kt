package io.github.hansoda.trace.route

/**
 * The days a video covers: sorted, separate ranges of local epoch days, both ends included.
 * Neighbouring days always share a range, so ranges are at least a day apart and each gap
 * between them is a jump in the video.
 */
class DaySelection private constructor(private val starts: LongArray, private val ends: LongArray) {
    val rangeCount: Int get() = starts.size
    val first: Long get() = starts[0]
    val last: Long get() = ends[ends.size - 1]
    val isRange: Boolean get() = starts.size == 1
    val dayCount: Int get() = starts.indices.sumOf { (ends[it] - starts[it] + 1).toInt() }

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

    fun shifted(days: Long): DaySelection =
        DaySelection(LongArray(starts.size) { starts[it] + days }, LongArray(ends.size) { ends[it] + days })

    /** Adds or removes one day; null if that would leave nothing selected. */
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

    /** "20000-20006,20010-20010" */
    fun encode(): String = starts.indices.joinToString(",") { "${starts[it]}-${ends[it]}" }

    override fun equals(other: Any?): Boolean =
        other is DaySelection && starts.contentEquals(other.starts) && ends.contentEquals(other.ends)

    override fun hashCode(): Int = 31 * starts.contentHashCode() + ends.contentHashCode()

    override fun toString(): String = "DaySelection(${encode()})"

    companion object {
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
            val ranges = text.split(',').map { part ->
                val bounds = part.split('-', limit = 2)
                val start = bounds.getOrNull(0)?.trim()?.toLongOrNull() ?: return null
                val end = bounds.getOrNull(1)?.trim()?.toLongOrNull() ?: return null
                start to end
            }
            return ofRanges(ranges)
        }
    }
}
