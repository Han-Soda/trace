package io.github.hansoda.trace.route

import kotlin.math.abs

/**
 * Ranks polyline vertices by Visvalingam–Whyatt importance: the area of the triangle each
 * point forms with its neighbours when it is removed. Keeping the [count] lowest ranks gives
 * the best [count]-point approximation the algorithm can find, so the "Travel points" slider
 * only has to filter by rank instead of simplifying again.
 *
 * @param fixed points that must survive any simplification, such as the ends of separate days;
 * the first and last points always do.
 * @return rank of each vertex; 0 and 1 are the endpoints, then the other fixed points, and
 * higher ranks matter less.
 */
fun visvalingamRanks(x: DoubleArray, y: DoubleArray, fixed: BooleanArray? = null): IntArray {
    val n = x.size
    val ranks = IntArray(n)
    if (n <= 2) {
        for (i in 0 until n) ranks[i] = i
        return ranks
    }
    val pinned = BooleanArray(n) { it == 0 || it == n - 1 || fixed?.get(it) == true }
    val previous = IntArray(n) { it - 1 }
    val next = IntArray(n) { it + 1 }
    val area = DoubleArray(n)
    val heap = MinHeap(n)
    for (i in 1 until n - 1) {
        if (pinned[i]) continue
        area[i] = triangleArea(x, y, i - 1, i, i + 1)
        heap.push(i, area[i])
    }
    // Removed points get ranks from the back, so the last survivors rank highest.
    var nextRank = n - 1
    var floor = 0.0
    while (heap.size > 0) {
        val i = heap.pop()
        ranks[i] = nextRank--
        // A point's effective area never drops below the one removed before it, which keeps
        // the order stable when removals flatten their neighbours.
        floor = maxOf(floor, area[i])
        val before = previous[i]
        val after = next[i]
        next[before] = after
        previous[after] = before
        if (!pinned[before]) {
            area[before] = maxOf(floor, triangleArea(x, y, previous[before], before, after))
            heap.update(before, area[before])
        }
        if (!pinned[after]) {
            area[after] = maxOf(floor, triangleArea(x, y, before, after, next[after]))
            heap.update(after, area[after])
        }
    }
    ranks[0] = 0
    ranks[n - 1] = 1
    var rank = 2
    for (i in 1 until n - 1) if (pinned[i]) ranks[i] = rank++
    return ranks
}

private fun triangleArea(x: DoubleArray, y: DoubleArray, a: Int, b: Int, c: Int): Double =
    abs((x[b] - x[a]) * (y[c] - y[a]) - (x[c] - x[a]) * (y[b] - y[a])) * 0.5

/** Binary min-heap of vertex indices keyed by area, with position tracking for updates. */
private class MinHeap(capacity: Int) {
    private val items = IntArray(capacity)
    private val keys = DoubleArray(capacity)
    private val position = IntArray(capacity) { -1 }
    var size = 0
        private set

    fun push(item: Int, key: Double) {
        items[size] = item
        keys[item] = key
        position[item] = size
        size++
        siftUp(size - 1)
    }

    fun pop(): Int {
        val top = items[0]
        size--
        position[top] = -1
        if (size > 0) {
            items[0] = items[size]
            position[items[0]] = 0
            siftDown(0)
        }
        return top
    }

    fun update(item: Int, key: Double) {
        val at = position[item]
        if (at < 0) return
        val old = keys[item]
        keys[item] = key
        if (key < old) siftUp(at) else siftDown(at)
    }

    private fun less(a: Int, b: Int): Boolean {
        val ka = keys[items[a]]
        val kb = keys[items[b]]
        // Ties break by index so the result doesn't depend on heap layout.
        return ka < kb || (ka == kb && items[a] < items[b])
    }

    private fun swap(a: Int, b: Int) {
        val item = items[a]
        items[a] = items[b]
        items[b] = item
        position[items[a]] = a
        position[items[b]] = b
    }

    private fun siftUp(start: Int) {
        var child = start
        while (child > 0) {
            val parent = (child - 1) / 2
            if (!less(child, parent)) return
            swap(child, parent)
            child = parent
        }
    }

    private fun siftDown(start: Int) {
        var parent = start
        while (true) {
            val left = parent * 2 + 1
            if (left >= size) return
            val right = left + 1
            val child = if (right < size && less(right, left)) right else left
            if (!less(child, parent)) return
            swap(child, parent)
            parent = child
        }
    }
}
