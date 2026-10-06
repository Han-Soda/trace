package io.github.hansoda.trace.route

import io.github.hansoda.trace.data.Timeline

/**
 * Stretches of time whose fixes were removed by hand, usually GPS errors. Kept as sorted,
 * merged intervals of epoch milliseconds with both ends included; a single removed fix is an
 * interval of one instant. Matching by time keeps removals valid when a newer export of the
 * same history is imported.
 */
class Exclusions private constructor(private val starts: LongArray, private val ends: LongArray) {
    val size: Int get() = starts.size
    val isEmpty: Boolean get() = starts.isEmpty()

    operator fun contains(time: Long): Boolean {
        // Last interval starting at or before the time.
        var low = 0
        var high = starts.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (starts[mid] <= time) low = mid + 1 else high = mid
        }
        return low > 0 && time <= ends[low - 1]
    }

    fun plus(start: Long, end: Long): Exclusions {
        val from = minOf(start, end)
        val to = maxOf(start, end)
        val outStarts = ArrayList<Long>(size + 1)
        val outEnds = ArrayList<Long>(size + 1)
        var placed = false
        fun append(s: Long, e: Long) {
            if (outEnds.isNotEmpty() && s <= outEnds.last()) {
                outEnds[outEnds.size - 1] = maxOf(outEnds.last(), e)
            } else {
                outStarts += s
                outEnds += e
            }
        }
        for (k in starts.indices) {
            if (!placed && from < starts[k]) {
                append(from, to)
                placed = true
            }
            append(starts[k], ends[k])
        }
        if (!placed) append(from, to)
        return Exclusions(outStarts.toLongArray(), outEnds.toLongArray())
    }

    /** How many fixes of [timeline] these remove. */
    fun countIn(timeline: Timeline): Int {
        var count = 0
        for (k in starts.indices) {
            count += timeline.lowerBound(if (ends[k] == Long.MAX_VALUE) ends[k] else ends[k] + 1) - timeline.lowerBound(starts[k])
        }
        return count
    }

    /** One "start-end" interval per line. */
    fun encode(): String = starts.indices.joinToString("\n") { "${starts[it]}-${ends[it]}" }

    override fun equals(other: Any?): Boolean =
        other is Exclusions && starts.contentEquals(other.starts) && ends.contentEquals(other.ends)

    override fun hashCode(): Int = 31 * starts.contentHashCode() + ends.contentHashCode()

    override fun toString(): String = "Exclusions(${encode().replace('\n', ',')})"

    companion object {
        val NONE = Exclusions(LongArray(0), LongArray(0))

        /** Reads [encode]d text, skipping lines it doesn't understand. */
        fun decode(text: String?): Exclusions {
            var result = NONE
            text?.lineSequence()?.forEach { line ->
                val bounds = line.trim().split('-', limit = 2)
                val start = bounds.getOrNull(0)?.toLongOrNull()
                val end = bounds.getOrNull(1)?.toLongOrNull()
                if (start != null && end != null) result = result.plus(start, end)
            }
            return result
        }
    }
}
