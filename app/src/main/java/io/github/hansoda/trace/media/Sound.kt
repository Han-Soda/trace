package io.github.hansoda.trace.media

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Clip sound as Trace keeps it: interleaved stereo 16-bit samples at [RATE]. */
object Sound {
    const val RATE = 48_000
    const val CHANNELS = 2

    /** [left] and [right] at [from] samples a second, as interleaved stereo at [to]. */
    fun resample(left: ShortArray, right: ShortArray, from: Int, to: Int): ShortArray {
        val count = left.size
        if (count == 0) return ShortArray(0)
        val frames = if (from == to) count else ((count.toLong() * to) / from).toInt().coerceAtLeast(1)
        val out = ShortArray(frames * CHANNELS)
        for (k in 0 until frames) {
            // Straight lines between neighbouring samples: plenty for the sound of a clip.
            val position = if (from == to) k.toDouble() else k.toDouble() * from / to
            val i = position.toInt().coerceAtMost(count - 1)
            val j = (i + 1).coerceAtMost(count - 1)
            val t = position - i
            out[2 * k] = (left[i] + (left[j] - left[i]) * t).toInt().toShort()
            out[2 * k + 1] = (right[i] + (right[j] - right[i]) * t).toInt().toShort()
        }
        return out
    }

    fun write(file: File, samples: ShortArray) {
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.asShortBuffer().put(samples)
        file.writeBytes(bytes.array())
    }

    fun read(file: File): ShortArray? {
        if (!file.isFile) return null
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val samples = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        return samples
    }
}
