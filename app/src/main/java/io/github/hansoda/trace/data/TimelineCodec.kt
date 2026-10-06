package io.github.hansoda.trace.data

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * Saves an imported [Timeline] in a compact binary file, so the app opens instantly next time
 * instead of parsing a JSON export of hundreds of megabytes again.
 */
object TimelineCodec {
    private const val MAGIC = 0x54524345 // "TRCE"
    private const val VERSION = 1
    private const val HEADER_BYTES = 16

    fun write(timeline: Timeline, file: File) {
        val temp = File(file.parentFile, file.name + ".tmp")
        RandomAccessFile(temp, "rw").use { raf ->
            raf.setLength(0)
            val channel = raf.channel
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            header.putInt(MAGIC).putInt(VERSION).putInt(timeline.size).putInt(0).flip()
            channel.writeFully(header)
            writeColumn(channel, timeline.size, 8) { buffer, from, count -> buffer.asLongBuffer().put(timeline.times, from, count) }
            writeColumn(channel, timeline.size, 4) { buffer, from, count -> buffer.asIntBuffer().put(timeline.latE7, from, count) }
            writeColumn(channel, timeline.size, 4) { buffer, from, count -> buffer.asIntBuffer().put(timeline.lonE7, from, count) }
            writeColumn(channel, timeline.size, 2) { buffer, from, count -> buffer.asShortBuffer().put(timeline.offsets, from, count) }
            channel.force(false)
        }
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Couldn't save the timeline")
        }
    }

    fun read(file: File): Timeline {
        RandomAccessFile(file, "r").use { raf ->
            val channel = raf.channel
            val header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
            channel.readFully(header)
            header.flip()
            if (header.int != MAGIC || header.int != VERSION) throw IOException("Unknown timeline cache")
            val size = header.int
            if (size < 0 || channel.size() != HEADER_BYTES + size.toLong() * 18) throw IOException("Damaged timeline cache")
            val times = LongArray(size)
            val lats = IntArray(size)
            val lons = IntArray(size)
            val offsets = ShortArray(size)
            readColumn(channel, size, 8) { buffer, from, count -> buffer.asLongBuffer().get(times, from, count) }
            readColumn(channel, size, 4) { buffer, from, count -> buffer.asIntBuffer().get(lats, from, count) }
            readColumn(channel, size, 4) { buffer, from, count -> buffer.asIntBuffer().get(lons, from, count) }
            readColumn(channel, size, 2) { buffer, from, count -> buffer.asShortBuffer().get(offsets, from, count) }
            return Timeline(times, lats, lons, offsets)
        }
    }

    private const val CHUNK = 64 * 1024

    private inline fun writeColumn(channel: FileChannel, size: Int, width: Int, fill: (ByteBuffer, Int, Int) -> Unit) {
        val buffer = ByteBuffer.allocate(CHUNK * width).order(ByteOrder.LITTLE_ENDIAN)
        var from = 0
        while (from < size) {
            val count = minOf(CHUNK, size - from)
            buffer.clear()
            fill(buffer, from, count)
            buffer.limit(count * width)
            channel.writeFully(buffer)
            from += count
        }
    }

    private inline fun readColumn(channel: FileChannel, size: Int, width: Int, drain: (ByteBuffer, Int, Int) -> Unit) {
        val buffer = ByteBuffer.allocate(CHUNK * width).order(ByteOrder.LITTLE_ENDIAN)
        var from = 0
        while (from < size) {
            val count = minOf(CHUNK, size - from)
            buffer.clear()
            buffer.limit(count * width)
            channel.readFully(buffer)
            buffer.flip()
            drain(buffer, from, count)
            from += count
        }
    }

    private fun FileChannel.writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) write(buffer)
    }

    private fun FileChannel.readFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (read(buffer) < 0) throw IOException("Damaged timeline cache")
        }
    }
}
