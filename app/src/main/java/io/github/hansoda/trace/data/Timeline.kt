package io.github.hansoda.trace.data

/**
 * Every location fix from a Timeline export, sorted by time. Stored column by column so a
 * multi-year history with a million fixes stays around 18 MB.
 */
class Timeline(
    val times: LongArray,
    val latE7: IntArray,
    val lonE7: IntArray,
    /** Local UTC offset in minutes at each fix, or [NO_OFFSET] when the export didn't say. */
    val offsets: ShortArray,
) {
    init {
        require(latE7.size == times.size && lonE7.size == times.size && offsets.size == times.size)
    }

    val size: Int get() = times.size

    fun isEmpty(): Boolean = times.isEmpty()

    fun lat(index: Int): Double = latE7[index] / 1e7

    fun lon(index: Int): Double = lonE7[index] / 1e7

    /** Index of the first fix at or after [time], or [size] if there is none. */
    fun lowerBound(time: Long): Int {
        var low = 0
        var high = times.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (times[mid] < time) low = mid + 1 else high = mid
        }
        return low
    }

    companion object {
        const val NO_OFFSET: Short = Short.MIN_VALUE
        val EMPTY = Timeline(LongArray(0), IntArray(0), IntArray(0), ShortArray(0))
    }
}

/** Receives fixes from [TimelineParser] in whatever order the file lists them. */
interface PointSink {
    /**
     * @param precise false for raw device signals, which only fill in times that the
     * processed Timeline doesn't cover.
     */
    fun add(time: Long, latE7: Int, lonE7: Int, offsetMinutes: Int, precise: Boolean)

    /** Marks a stretch of time that processed Timeline data accounts for. */
    fun covered(start: Long, end: Long) {}
}

/** Collects fixes and turns them into a sorted, de-duplicated [Timeline]. */
class TimelineBuilder : PointSink {
    private val processed = Column()
    private val raw = Column()
    private var spans = LongArray(512)
    private var spanCount = 0

    val count: Int get() = processed.size + raw.size

    override fun add(time: Long, latE7: Int, lonE7: Int, offsetMinutes: Int, precise: Boolean) {
        (if (precise) processed else raw).add(time, latE7, lonE7, offsetMinutes)
    }

    override fun covered(start: Long, end: Long) {
        if (end < start) return
        if (spanCount * 2 == spans.size) spans = spans.copyOf(spans.size * 2)
        spans[spanCount * 2] = start
        spans[spanCount * 2 + 1] = end
        spanCount++
    }

    /**
     * Processed segments are cleaner than raw signals, so raw fixes are only kept where no
     * processed segment covers their time, such as the latest days before Google processes them.
     */
    fun build(): Timeline {
        val merged = Column()
        merged.addAll(processed) { true }
        val starts = LongArray(spanCount) { spans[it * 2] }
        val order = sortedOrder(starts, spanCount)
        val mergedStarts = LongArray(spanCount)
        val mergedEnds = LongArray(spanCount)
        var count = 0
        for (index in order) {
            val start = spans[index * 2]
            val end = spans[index * 2 + 1]
            if (count > 0 && start <= mergedEnds[count - 1]) {
                mergedEnds[count - 1] = maxOf(mergedEnds[count - 1], end)
            } else {
                mergedStarts[count] = start
                mergedEnds[count] = end
                count++
            }
        }
        merged.addAll(raw) { time ->
            // Last span starting at or before this time.
            var low = 0
            var high = count
            while (low < high) {
                val mid = (low + high) ushr 1
                if (mergedStarts[mid] <= time) low = mid + 1 else high = mid
            }
            low == 0 || time > mergedEnds[low - 1]
        }
        return merged.toTimeline()
    }

    private class Column {
        var size = 0
        private var times = LongArray(1024)
        private var lats = IntArray(1024)
        private var lons = IntArray(1024)
        private var offsets = ShortArray(1024)

        fun add(time: Long, lat: Int, lon: Int, offset: Int) {
            append(time, lat, lon, if (offset in -1080..1080) offset.toShort() else Timeline.NO_OFFSET)
        }

        private fun append(time: Long, lat: Int, lon: Int, offset: Short) {
            if (size == times.size) {
                val capacity = size * 2
                times = times.copyOf(capacity)
                lats = lats.copyOf(capacity)
                lons = lons.copyOf(capacity)
                offsets = offsets.copyOf(capacity)
            }
            times[size] = time
            lats[size] = lat
            lons[size] = lon
            offsets[size] = offset
            size++
        }

        inline fun addAll(other: Column, keep: (Long) -> Boolean) {
            for (i in 0 until other.size) {
                if (keep(other.times[i])) append(other.times[i], other.lats[i], other.lons[i], other.offsets[i])
            }
        }

        fun toTimeline(): Timeline {
            val order = sortedOrder(times, size)
            val outTimes = LongArray(size)
            val outLats = IntArray(size)
            val outLons = IntArray(size)
            val outOffsets = ShortArray(size)
            var count = 0
            for (index in order) {
                val time = times[index]
                val lat = lats[index]
                val lon = lons[index]
                if (count > 0 && outTimes[count - 1] == time && outLats[count - 1] == lat && outLons[count - 1] == lon) {
                    if (outOffsets[count - 1] == Timeline.NO_OFFSET) outOffsets[count - 1] = offsets[index]
                    continue
                }
                outTimes[count] = time
                outLats[count] = lat
                outLons[count] = lon
                outOffsets[count] = offsets[index]
                count++
            }
            return Timeline(outTimes.copyOf(count), outLats.copyOf(count), outLons.copyOf(count), outOffsets.copyOf(count))
        }
    }
}

/** Indices of the first [size] entries of [keys] in ascending, stable order. */
internal fun sortedOrder(keys: LongArray, size: Int): IntArray {
    var order = IntArray(size) { it }
    var sorted = true
    for (i in 1 until size) {
        if (keys[i] < keys[i - 1]) {
            sorted = false
            break
        }
    }
    if (sorted) return order
    // Bottom-up merge sort on primitive arrays; a boxed sort would allocate millions of objects.
    var buffer = IntArray(size)
    var width = 1
    while (width < size) {
        var start = 0
        while (start < size) {
            val middle = minOf(start + width, size)
            val end = minOf(start + 2 * width, size)
            var left = start
            var right = middle
            var out = start
            while (left < middle && right < end) {
                buffer[out++] = if (keys[order[right]] < keys[order[left]]) order[right++] else order[left++]
            }
            while (left < middle) buffer[out++] = order[left++]
            while (right < end) buffer[out++] = order[right++]
            start = end
        }
        val swap = order
        order = buffer
        buffer = swap
        width *= 2
    }
    return order
}
