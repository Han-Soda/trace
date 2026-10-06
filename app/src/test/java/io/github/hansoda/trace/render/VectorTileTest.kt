package io.github.hansoda.trace.render

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorTileTest {
    // A tiny protocol buffer writer, enough to build test tiles.
    private class Writer {
        val out = ByteArrayOutputStream()

        fun varint(value: Long) {
            var v = value
            while (true) {
                val b = (v and 0x7F).toInt()
                v = v ushr 7
                if (v == 0L) {
                    out.write(b)
                    return
                }
                out.write(b or 0x80)
            }
        }

        fun key(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun bytes(field: Int, data: ByteArray) {
            key(field, 2)
            varint(data.size.toLong())
            out.write(data)
        }

        fun message(field: Int, build: Writer.() -> Unit) = bytes(field, Writer().apply(build).out.toByteArray())

        fun packed(field: Int, values: List<Int>) = bytes(field, Writer().apply { values.forEach { varint(it.toLong() and 0xFFFFFFFFL) } }.out.toByteArray())
    }

    private fun zigzag(n: Int) = (n shl 1) xor (n shr 31)

    private fun command(id: Int, count: Int) = (id and 7) or (count shl 3)

    private fun tile(): ByteArray = Writer().apply {
        message(3) {
            varint(((15 shl 3) or 0).toLong()); varint(2)
            bytes(1, "transportation".toByteArray())
            message(2) {
                packed(2, listOf(0, 0))
                key(3, 0); varint(2)
                // A line from (10, 20) to (30, 20) to (30, 50).
                packed(4, listOf(command(1, 1), zigzag(10), zigzag(20), command(2, 2), zigzag(20), zigzag(0), zigzag(0), zigzag(30)))
            }
            bytes(3, "class".toByteArray())
            message(4) { bytes(1, "primary".toByteArray()) }
            key(5, 0); varint(4096)
        }
        message(3) {
            bytes(1, "place".toByteArray())
            message(2) {
                packed(2, listOf(0, 0, 1, 1))
                key(3, 0); varint(1)
                packed(4, listOf(command(1, 1), zigzag(2048), zigzag(1024)))
            }
            bytes(3, "name".toByteArray())
            bytes(3, "rank".toByteArray())
            message(4) { bytes(1, "Lisboa".toByteArray()) }
            message(4) { key(6, 0); varint(zigzag(-3).toLong()) }
            key(5, 0); varint(4096)
        }
    }.out.toByteArray()

    @Test
    fun readsLayersFeaturesAndProperties() {
        val tile = VectorTile.decode(tile())
        assertEquals(setOf("transportation", "place"), tile.layerNames)

        val road = tile.layer("transportation")!!.features.single()
        assertEquals(VectorTile.LINE, road.type)
        assertEquals("primary", road.string("class"))
        assertArrayEquals(intArrayOf(10, 20, 30, 20, 30, 50), road.coordinates)
        assertArrayEquals(intArrayOf(0, 3), road.parts)
        assertEquals(10, road.minX)
        assertEquals(50, road.maxY)
        assertNull(road.string("name"))

        val place = tile.layer("place")!!.features.single()
        assertEquals(VectorTile.POINT, place.type)
        assertEquals("Lisboa", place.string("name"))
        assertEquals(-3.0, place.number("rank")!!, 0.0)
        assertEquals(4096, tile.layer("place")!!.extent)
    }

    @Test
    fun readsGzippedTiles() {
        val packed = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(tile()) } }.toByteArray()
        assertTrue(packed[0] == 0x1F.toByte())
        assertEquals(setOf("transportation", "place"), VectorTile.decode(packed).layerNames)
    }

    @Test
    fun namesVectorTileSets() {
        assertEquals("vector-paper", MapStyle.PAPER.tileSet(true))
        assertEquals(MapStyle.INK to false, MapStyle.vectorSet(MapStyle.INK.tileSet(false)))
        assertEquals(MapStyle.STREETS to true, MapStyle.vectorSet(MapStyle.STREETS.tileSet(true)))
        assertNull(MapStyle.vectorSet(MapStyle.LIGHT.tileSet(true)))
        assertEquals(MapStyle.PAPER, MapStyle.fromId("nonsense"))
    }
}
