package io.github.hansoda.trace.render

import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.zip.GZIPInputStream

/**
 * A Mapbox Vector Tile, the format OpenFreeMap serves: named layers of points, lines and
 * polygons in tile units, each with a few properties. Geometry is decoded on first use.
 */
class VectorTile private constructor(private val layers: Map<String, Layer>) {
    val layerNames: Set<String> get() = layers.keys

    fun layer(name: String): Layer? = layers[name]

    class Layer internal constructor(
        val name: String,
        /** Tile units across the tile, usually 4096. */
        val extent: Int,
        val features: List<Feature>,
        internal val keys: Array<String>,
        internal val values: Array<Any?>,
    ) {
        private val keyIndex: Map<String, Int> = keys.withIndex().associate { (i, key) -> key to i }

        internal fun indexOf(key: String): Int = keyIndex[key] ?: -1
    }

    class Feature internal constructor(
        private val layer: () -> Layer,
        /** [POINT], [LINE] or [POLYGON]. */
        val type: Int,
        private val tags: IntArray,
        private val bytes: ByteArray,
        private val geometryStart: Int,
        private val geometryEnd: Int,
    ) {
        private var decoded: Geometry? = null

        /** Coordinates as x, y pairs in tile units; see [parts]. */
        val coordinates: IntArray get() = geometry().coordinates

        /** Point index where each ring, line or point group starts, plus the total point count. */
        val parts: IntArray get() = geometry().parts
        val minX: Int get() = geometry().minX
        val minY: Int get() = geometry().minY
        val maxX: Int get() = geometry().maxX
        val maxY: Int get() = geometry().maxY

        fun value(key: String): Any? {
            val index = layer().indexOf(key)
            if (index < 0) return null
            var t = 0
            while (t + 1 < tags.size) {
                if (tags[t] == index) return layer().values.getOrNull(tags[t + 1])
                t += 2
            }
            return null
        }

        fun string(key: String): String? = value(key) as? String

        fun number(key: String): Double? = when (val v = value(key)) {
            is Number -> v.toDouble()
            is String -> v.toDoubleOrNull()
            is Boolean -> if (v) 1.0 else 0.0
            else -> null
        }

        private fun geometry(): Geometry = decoded ?: Geometry.decode(bytes, geometryStart, geometryEnd).also { decoded = it }
    }

    internal class Geometry(val coordinates: IntArray, val parts: IntArray, val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        companion object {
            fun decode(bytes: ByteArray, start: Int, end: Int): Geometry {
                val reader = Pbf(bytes, start, end)
                var coordinates = IntArray(64)
                var parts = IntArray(8)
                var points = 0
                var partCount = 0
                var x = 0
                var y = 0
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var maxY = Int.MIN_VALUE
                while (reader.hasMore()) {
                    val command = reader.varint().toInt()
                    val id = command and 7
                    val count = command ushr 3
                    when (id) {
                        MOVE_TO, LINE_TO -> repeat(count) {
                            x += zigzag(reader.varint())
                            y += zigzag(reader.varint())
                            if (id == MOVE_TO) {
                                if (partCount + 1 >= parts.size) parts = parts.copyOf(parts.size * 2)
                                parts[partCount++] = points
                            }
                            if (points * 2 + 2 > coordinates.size) coordinates = coordinates.copyOf(coordinates.size * 2)
                            coordinates[points * 2] = x
                            coordinates[points * 2 + 1] = y
                            points++
                            if (x < minX) minX = x
                            if (x > maxX) maxX = x
                            if (y < minY) minY = y
                            if (y > maxY) maxY = y
                        }
                        CLOSE_PATH -> Unit
                        else -> throw IOException("Unknown geometry command $id")
                    }
                }
                if (partCount + 1 > parts.size) parts = parts.copyOf(partCount + 1)
                parts[partCount] = points
                if (points == 0) {
                    minX = 0
                    minY = 0
                    maxX = 0
                    maxY = 0
                }
                return Geometry(coordinates.copyOf(points * 2), parts.copyOf(partCount + 1), minX, minY, maxX, maxY)
            }

            private const val MOVE_TO = 1
            private const val LINE_TO = 2
            private const val CLOSE_PATH = 7
        }
    }

    companion object {
        const val POINT = 1
        const val LINE = 2
        const val POLYGON = 3

        /** Reads a tile, gzipped or not. */
        fun decode(data: ByteArray): VectorTile {
            val bytes = if (data.size >= 2 && data[0] == 0x1F.toByte() && data[1] == 0x8B.toByte()) {
                GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
            } else {
                data
            }
            val layers = HashMap<String, Layer>()
            val reader = Pbf(bytes, 0, bytes.size)
            while (reader.hasMore()) {
                val (field, wire) = reader.key()
                if (field == 3 && wire == Pbf.LENGTH) {
                    val (start, end) = reader.lengthDelimited()
                    val layer = readLayer(bytes, start, end)
                    // The spec allows a name only once; keep the first.
                    if (layer.name !in layers) layers[layer.name] = layer
                } else {
                    reader.skip(wire)
                }
            }
            return VectorTile(layers)
        }

        private fun readLayer(bytes: ByteArray, start: Int, end: Int): Layer {
            val reader = Pbf(bytes, start, end)
            var name = ""
            var extent = 4096
            val keys = ArrayList<String>()
            val values = ArrayList<Any?>()
            val features = ArrayList<Feature>()
            lateinit var layer: Layer
            val self = { layer }
            while (reader.hasMore()) {
                val (field, wire) = reader.key()
                when {
                    field == 1 && wire == Pbf.LENGTH -> name = reader.string()
                    field == 2 && wire == Pbf.LENGTH -> {
                        val (featureStart, featureEnd) = reader.lengthDelimited()
                        readFeature(bytes, featureStart, featureEnd, self)?.let { features += it }
                    }
                    field == 3 && wire == Pbf.LENGTH -> keys += reader.string()
                    field == 4 && wire == Pbf.LENGTH -> {
                        val (valueStart, valueEnd) = reader.lengthDelimited()
                        values += readValue(bytes, valueStart, valueEnd)
                    }
                    field == 5 && wire == Pbf.VARINT -> extent = reader.varint().toInt().coerceAtLeast(1)
                    else -> reader.skip(wire)
                }
            }
            layer = Layer(name, extent, features, keys.toTypedArray(), values.toTypedArray())
            return layer
        }

        private fun readFeature(bytes: ByteArray, start: Int, end: Int, layer: () -> Layer): Feature? {
            val reader = Pbf(bytes, start, end)
            var type = 0
            var tags = IntArray(0)
            var geometryStart = -1
            var geometryEnd = -1
            while (reader.hasMore()) {
                val (field, wire) = reader.key()
                when {
                    field == 2 && wire == Pbf.LENGTH -> {
                        val (tagStart, tagEnd) = reader.lengthDelimited()
                        val tagReader = Pbf(bytes, tagStart, tagEnd)
                        val list = ArrayList<Int>()
                        while (tagReader.hasMore()) list += tagReader.varint().toInt()
                        tags = list.toIntArray()
                    }
                    field == 3 && wire == Pbf.VARINT -> type = reader.varint().toInt()
                    field == 4 && wire == Pbf.LENGTH -> {
                        val (geometryFrom, geometryTo) = reader.lengthDelimited()
                        geometryStart = geometryFrom
                        geometryEnd = geometryTo
                    }
                    else -> reader.skip(wire)
                }
            }
            if (geometryStart < 0 || type !in POINT..POLYGON) return null
            return Feature(layer, type, tags, bytes, geometryStart, geometryEnd)
        }

        private fun readValue(bytes: ByteArray, start: Int, end: Int): Any? {
            val reader = Pbf(bytes, start, end)
            var value: Any? = null
            while (reader.hasMore()) {
                val (field, wire) = reader.key()
                value = when {
                    field == 1 && wire == Pbf.LENGTH -> reader.string()
                    field == 2 && wire == Pbf.FIXED32 -> java.lang.Float.intBitsToFloat(reader.fixed32()).toDouble()
                    field == 3 && wire == Pbf.FIXED64 -> java.lang.Double.longBitsToDouble(reader.fixed64())
                    field == 4 && wire == Pbf.VARINT -> reader.varint()
                    field == 5 && wire == Pbf.VARINT -> reader.varint()
                    field == 6 && wire == Pbf.VARINT -> zigzag(reader.varint()).toLong()
                    field == 7 && wire == Pbf.VARINT -> reader.varint() != 0L
                    else -> {
                        reader.skip(wire)
                        value
                    }
                }
            }
            return value
        }

        private fun zigzag(value: Long): Int = ((value ushr 1) xor -(value and 1)).toInt()
    }
}

/** Just enough protocol buffers for vector tiles. */
internal class Pbf(private val bytes: ByteArray, start: Int, private val end: Int) {
    private var position = start

    fun hasMore(): Boolean = position < end

    fun key(): Pair<Int, Int> {
        val key = varint()
        return (key ushr 3).toInt() to (key and 7).toInt()
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (position >= end) throw IOException("Truncated vector tile")
            val b = bytes[position++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IOException("Damaged vector tile")
        }
    }

    fun fixed32(): Int {
        if (position + 4 > end) throw IOException("Truncated vector tile")
        var value = 0
        for (k in 0 until 4) value = value or ((bytes[position + k].toInt() and 0xFF) shl (8 * k))
        position += 4
        return value
    }

    fun fixed64(): Long {
        if (position + 8 > end) throw IOException("Truncated vector tile")
        var value = 0L
        for (k in 0 until 8) value = value or ((bytes[position + k].toLong() and 0xFF) shl (8 * k))
        position += 8
        return value
    }

    /** Start and end of the next length-delimited field. */
    fun lengthDelimited(): Pair<Int, Int> {
        val length = varint().toInt()
        if (length < 0 || position + length > end) throw IOException("Truncated vector tile")
        val start = position
        position += length
        return start to position
    }

    fun string(): String {
        val (start, end) = lengthDelimited()
        return String(bytes, start, end - start, Charsets.UTF_8)
    }

    fun skip(wire: Int) {
        when (wire) {
            VARINT -> varint()
            FIXED64 -> position += 8
            LENGTH -> lengthDelimited()
            FIXED32 -> position += 4
            else -> throw IOException("Unknown wire type $wire")
        }
        if (position > end) throw IOException("Truncated vector tile")
    }

    companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LENGTH = 2
        const val FIXED32 = 5
    }
}
