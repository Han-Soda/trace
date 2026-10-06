package io.github.hansoda.trace.render

import java.io.IOException

/**
 * Coastlines and large lakes from Natural Earth (public domain), drawn by the Paper and Ink
 * styles. Coordinates are Web Mercator world units scaled by [SCALE]; see
 * `tools/make_land_asset.py` for the file format.
 */
class LandShapes private constructor(
    /** True where the ring is a lake rather than land. */
    val lake: BooleanArray,
    /** Ring r uses points `start[r] until start[r + 1]`. */
    val start: IntArray,
    val x: IntArray,
    val y: IntArray,
    val minX: IntArray,
    val minY: IntArray,
    val maxX: IntArray,
    val maxY: IntArray,
) {
    val ringCount: Int get() = lake.size

    companion object {
        const val SCALE = 1 shl 22

        fun decode(bytes: ByteArray): LandShapes {
            val input = Reader(bytes)
            if (bytes.size < 5 || String(bytes, 0, 4, Charsets.US_ASCII) != "LAND" || bytes[4].toInt() != 1) {
                throw IOException("Not a land shapes file")
            }
            input.position = 5
            val rings = input.varint().toInt()
            val lake = BooleanArray(rings)
            val start = IntArray(rings + 1)
            val minX = IntArray(rings)
            val minY = IntArray(rings)
            val maxX = IntArray(rings)
            val maxY = IntArray(rings)
            var xs = IntArray(1 shl 16)
            var ys = IntArray(1 shl 16)
            var count = 0
            for (r in 0 until rings) {
                lake[r] = input.byte() == 1
                val points = input.varint().toInt()
                start[r] = count
                if (count + points > xs.size) {
                    val capacity = maxOf(xs.size * 2, count + points)
                    xs = xs.copyOf(capacity)
                    ys = ys.copyOf(capacity)
                }
                var px = 0L
                var py = 0L
                var lowX = Int.MAX_VALUE
                var lowY = Int.MAX_VALUE
                var highX = Int.MIN_VALUE
                var highY = Int.MIN_VALUE
                for (p in 0 until points) {
                    if (p == 0) {
                        px = input.varint()
                        py = input.varint()
                    } else {
                        px += unzigzag(input.varint())
                        py += unzigzag(input.varint())
                    }
                    val ix = px.toInt()
                    val iy = py.toInt()
                    xs[count] = ix
                    ys[count] = iy
                    count++
                    lowX = minOf(lowX, ix)
                    lowY = minOf(lowY, iy)
                    highX = maxOf(highX, ix)
                    highY = maxOf(highY, iy)
                }
                minX[r] = lowX
                minY[r] = lowY
                maxX[r] = highX
                maxY[r] = highY
            }
            start[rings] = count
            return LandShapes(lake, start, xs.copyOf(count), ys.copyOf(count), minX, minY, maxX, maxY)
        }

        private fun unzigzag(value: Long): Long = (value ushr 1) xor -(value and 1)
    }

    private class Reader(private val bytes: ByteArray) {
        var position = 0

        fun byte(): Int {
            if (position >= bytes.size) throw IOException("Truncated land shapes file")
            return bytes[position++].toInt() and 0xFF
        }

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = byte()
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > 63) throw IOException("Damaged land shapes file")
            }
        }
    }
}
