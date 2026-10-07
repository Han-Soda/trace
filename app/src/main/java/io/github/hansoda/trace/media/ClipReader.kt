package io.github.hansoda.trace.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Decodes part of a video the way Trace keeps clips: JPEG frames at the video's own pace, up to
 * 30 a second and no larger than [side] pixels, and its sound as 48 kHz stereo 16-bit PCM.
 * Frames are decoded one after another with the phone's decoder, which is quick even for long
 * or 4K videos.
 */
internal class ClipReader(private val context: Context, private val side: Int, private val quality: Int) {
    class Frames(val count: Int, val fps: Double, val width: Int, val height: Int)

    /**
     * Writes the frames of [startUs] until [endUs] to [frame] files, the first one also as the
     * [thumbnail]. Null when the video can't be decoded this way.
     */
    fun frames(
        uri: Uri, startUs: Long, endUs: Long, frame: (Int) -> File, thumbnail: (Bitmap) -> Unit,
        isActive: () -> Boolean, onProgress: (Float) -> Unit,
    ): Frames? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { mime(extractor.getTrackFormat(it)).startsWith("video/") } ?: return null
            val format = extractor.getTrackFormat(track)
            val rotation = number(format, MediaFormat.KEY_ROTATION) ?: 0
            // Keep the video's own pace when it's slower than 30 a second, as films often are.
            val nominal = number(format, MediaFormat.KEY_FRAME_RATE) ?: 0
            val fps = if (nominal in 12..30) nominal else MAX_FPS
            val step = 1_000_000.0 / fps
            val count = max(1, ceil((endUs - startUs) / step - 1e-6).toInt())

            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            codec = decoder(format)
            codec.configure(format, null, null, 0)
            codec.start()

            val info = MediaCodec.BufferInfo()
            val converter = Converter()
            var next = 0
            var inputDone = false
            var idle = 0
            var width = 0
            var height = 0
            while (next < count) {
                if (!isActive()) return null
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        // Pictures arrive out of order; a little past the end covers those it needs.
                        if (size < 0 || time > endUs + REORDER_US) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    converter.colors(codec.outputFormat)
                    continue
                }
                if (index < 0) {
                    // About ten seconds without a frame: the decoder is stuck.
                    if (++idle > 1_000) throw IOException("The decoder stopped")
                    continue
                }
                idle = 0
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                // The frames this one stands for: those due at its time, give or take half a frame.
                val time = info.presentationTimeUs
                var first = -1
                var last = -1
                while (info.size > 0 && next < count && time >= startUs + next * step - step / 2) {
                    if (first < 0) first = next
                    last = next
                    next++
                }
                if (first >= 0) {
                    val bitmap = codec.getOutputImage(index)?.use { converter.convert(it, rotation) }
                    codec.releaseOutputBuffer(index, false)
                    if (bitmap == null) throw IOException("The decoder gave no picture")
                    width = bitmap.width
                    height = bitmap.height
                    val file = frame(first)
                    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }
                    if (first == 0) thumbnail(bitmap)
                    bitmap.recycle()
                    // Where the video skipped a beat, the same picture stays up.
                    for (k in first + 1..last) file.copyTo(frame(k), overwrite = true)
                    onProgress(next.toFloat() / count)
                } else {
                    codec.releaseOutputBuffer(index, false)
                }
                if (ended) break
            }
            return if (next == 0) null else Frames(next, fps.toDouble(), width, height)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    /**
     * The sound of [startUs] until [endUs] as interleaved 48 kHz stereo samples, or null when
     * the video has none that this phone can decode.
     */
    fun sound(uri: Uri, startUs: Long, endUs: Long, isActive: () -> Boolean): ShortArray? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { mime(extractor.getTrackFormat(it)).startsWith("audio/") } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            codec = decoder(format)
            codec.configure(format, null, null, 0)
            codec.start()

            var rate = number(format, MediaFormat.KEY_SAMPLE_RATE) ?: 44_100
            var channels = number(format, MediaFormat.KEY_CHANNEL_COUNT) ?: 2
            var float = false
            val left = ShortList()
            val right = ShortList()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var idle = 0
            while (true) {
                if (!isActive()) return null
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        val size = extractor.readSampleData(buffer, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val output = codec.outputFormat
                    rate = number(output, MediaFormat.KEY_SAMPLE_RATE) ?: rate
                    channels = number(output, MediaFormat.KEY_CHANNEL_COUNT) ?: channels
                    float = number(output, MediaFormat.KEY_PCM_ENCODING) == PCM_FLOAT
                    continue
                }
                if (index < 0) {
                    if (++idle > 1_000) throw IOException("The decoder stopped")
                    continue
                }
                idle = 0
                val buffer = codec.getOutputBuffer(index)
                if (buffer != null && info.size > 0 && channels > 0) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val data = buffer.slice().order(ByteOrder.nativeOrder())
                    val bytes = if (float) 4 else 2
                    val count = info.size / (bytes * channels)
                    for (j in 0 until count) {
                        val time = info.presentationTimeUs + j * 1_000_000L / rate
                        if (time < startUs || time >= endUs) continue
                        val base = j * channels * bytes
                        val l = sample(data, base, float)
                        val r = if (channels > 1) sample(data, base + bytes, float) else l
                        left.add(l)
                        right.add(r)
                    }
                }
                val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val past = info.presentationTimeUs > endUs
                codec.releaseOutputBuffer(index, false)
                if (ended || past) break
            }
            if (left.size == 0) return null
            return Sound.resample(left.toArray(), right.toArray(), rate, Sound.RATE)
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    private fun sample(data: ByteBuffer, at: Int, float: Boolean): Short =
        if (float) (data.getFloat(at).coerceIn(-1f, 1f) * Short.MAX_VALUE).roundToInt().toShort() else data.getShort(at)

    private fun decoder(format: MediaFormat): MediaCodec {
        val name = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findDecoderForFormat(format) }.getOrNull()
        return if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime(format))
    }

    /**
     * Turns decoded pictures into upright bitmaps no larger than [side]: YUV to RGB at up to
     * twice that size, then scaled down smoothly.
     */
    private inner class Converter {
        private var luma = ByteArray(0)
        private var blue = ByteArray(0)
        private var red = ByteArray(0)
        private var pixels = IntArray(0)
        private var grid: Bitmap? = null
        private var yuv: Yuv? = null

        /** The colour standard and range the decoder reports, when it does. */
        fun colors(format: MediaFormat) {
            val standard = number(format, MediaFormat.KEY_COLOR_STANDARD) ?: return
            val range = number(format, MediaFormat.KEY_COLOR_RANGE)
            val sd = standard == MediaFormat.COLOR_STANDARD_BT601_PAL || standard == MediaFormat.COLOR_STANDARD_BT601_NTSC
            yuv = Yuv.of(hd = !sd, full = range == MediaFormat.COLOR_RANGE_FULL)
        }

        fun convert(image: Image, rotation: Int): Bitmap? {
            val crop = image.cropRect
            val sourceWidth = crop.width()
            val sourceHeight = crop.height()
            if (sourceWidth <= 0 || sourceHeight <= 0 || image.planes.size < 3) return null
            val long = max(sourceWidth, sourceHeight)
            val outScale = min(1.0, side.toDouble() / long)
            val outWidth = max(1, (sourceWidth * outScale).roundToInt())
            val outHeight = max(1, (sourceHeight * outScale).roundToInt())
            // Sample at up to twice the final size, so scaling down averages neighbours.
            val gridScale = min(1.0, 2.0 * side / long)
            val gridWidth = max(1, (sourceWidth * gridScale).roundToInt())
            val gridHeight = max(1, (sourceHeight * gridScale).roundToInt())

            val (y, u, v) = image.planes
            luma = copy(y.buffer, luma)
            blue = copy(u.buffer, blue)
            red = copy(v.buffer, red)
            // 10-bit pictures keep two bytes a sample; the high one is close enough.
            val wide = y.pixelStride >= 2
            val high = if (wide) 1 else 0
            val coefficients = yuv ?: Yuv.of(hd = long >= 1280, full = false)

            if (pixels.size < gridWidth * gridHeight) pixels = IntArray(gridWidth * gridHeight)
            for (gy in 0 until gridHeight) {
                val sy = crop.top + ((gy + 0.5) * sourceHeight / gridHeight).toInt()
                val lumaRow = sy * y.rowStride
                val chromaY = sy / 2
                val uRow = chromaY * u.rowStride
                val vRow = chromaY * v.rowStride
                val out = gy * gridWidth
                for (gx in 0 until gridWidth) {
                    val sx = crop.left + ((gx + 0.5) * sourceWidth / gridWidth).toInt()
                    val chromaX = sx / 2
                    val l = at(luma, lumaRow + sx * y.pixelStride + high)
                    val cb = at(blue, uRow + chromaX * u.pixelStride + high)
                    val cr = at(red, vRow + chromaX * v.pixelStride + high)
                    pixels[out + gx] = coefficients.rgb(l, cb, cr)
                }
            }
            var bitmap = grid?.takeIf { it.width == gridWidth && it.height == gridHeight }
                ?: Bitmap.createBitmap(gridWidth, gridHeight, Bitmap.Config.ARGB_8888).also {
                    grid?.recycle()
                    grid = it
                }
            bitmap.setPixels(pixels, 0, gridWidth, 0, 0, gridWidth, gridHeight)
            bitmap = Bitmap.createScaledBitmap(bitmap, outWidth, outHeight, true).let { if (it === grid) it.copy(Bitmap.Config.ARGB_8888, false) else it }
            if (rotation % 360 == 0) return bitmap
            val turned = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
            if (turned !== bitmap) bitmap.recycle()
            return turned
        }

        private fun at(bytes: ByteArray, index: Int): Int = if (index in bytes.indices) bytes[index].toInt() and 0xFF else 128

        private fun copy(buffer: ByteBuffer, into: ByteArray): ByteArray {
            val source = buffer.duplicate()
            source.position(0)
            val size = source.limit()
            val target = if (into.size >= size) into else ByteArray(size)
            source.get(target, 0, size)
            return target
        }
    }

    /** A growable list of samples. */
    private class ShortList {
        private var data = ShortArray(48_000)
        var size = 0
            private set

        fun add(value: Short) {
            if (size == data.size) data = data.copyOf(data.size * 2)
            data[size++] = value
        }

        fun toArray(): ShortArray = data.copyOf(size)
    }

    private companion object {
        const val MAX_FPS = 30
        const val TIMEOUT_US = 10_000L
        const val REORDER_US = 500_000L
        const val PCM_FLOAT = 4

        fun mime(format: MediaFormat): String = format.getString(MediaFormat.KEY_MIME).orEmpty()

        /** A whole number from [format], however the extractor stored it. */
        fun number(format: MediaFormat, key: String): Int? {
            if (!format.containsKey(key)) return null
            return runCatching { format.getInteger(key) }.getOrNull()
                ?: runCatching { format.getFloat(key).roundToInt() }.getOrNull()
                ?: runCatching { format.getLong(key).toInt() }.getOrNull()
        }
    }
}

/** YUV to RGB for the colour standards and ranges videos use. */
internal class Yuv private constructor(
    private val lumaOffset: Int,
    private val lumaScale: Int,
    private val redFromCr: Int,
    private val greenFromCb: Int,
    private val greenFromCr: Int,
    private val blueFromCb: Int,
) {
    /** An opaque colour from 8-bit [y], [cb] and [cr]. */
    fun rgb(y: Int, cb: Int, cr: Int): Int {
        val l = (y - lumaOffset) * lumaScale
        val u = cb - 128
        val v = cr - 128
        val r = clamp((l + redFromCr * v + 512) shr 10)
        val g = clamp((l - greenFromCb * u - greenFromCr * v + 512) shr 10)
        val b = clamp((l + blueFromCb * u + 512) shr 10)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(value: Int) = if (value < 0) 0 else if (value > 255) 255 else value

    companion object {
        // Limited range, BT.709 (HD) and BT.601 (SD); full range scales luma by 1.
        val HD = Yuv(16, 1192, 1836, 218, 546, 2163)
        val SD = Yuv(16, 1192, 1634, 401, 832, 2066)
        val HD_FULL = Yuv(0, 1024, 1613, 192, 479, 1900)
        val SD_FULL = Yuv(0, 1024, 1436, 352, 731, 1815)

        fun of(hd: Boolean, full: Boolean): Yuv = when {
            hd && full -> HD_FULL
            hd -> HD
            full -> SD_FULL
            else -> SD
        }
    }
}
