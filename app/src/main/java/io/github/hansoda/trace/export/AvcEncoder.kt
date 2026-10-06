package io.github.hansoda.trace.export

import android.graphics.Bitmap
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer

/** Encodes ARGB frames into an H.264 MP4 file with the platform encoder. */
class AvcEncoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    bitRate: Int,
    output: File,
) : AutoCloseable {
    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val info = MediaCodec.BufferInfo()
    private val pixels = IntArray(width * height)
    private val row = ByteArray(width)
    private var track = -1
    private var muxing = false
    private var frames = 0L

    init {
        require(width % 2 == 0 && height % 2 == 0) { "Video sizes must be even" }
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_STANDARD, MediaFormat.COLOR_STANDARD_BT709)
            setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
            setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
        }
        val name = VideoSizes.encoderFor(width, height, format)
            ?: throw IOException("This phone has no video encoder for ${width}×$height")
        codec = MediaCodec.createByCodecName(name)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(output.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        } catch (error: Exception) {
            codec.release()
            throw error
        }
    }

    fun encode(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val index = dequeueInput()
        val image = codec.getInputImage(index)
        if (image != null) {
            write(image)
        } else {
            val buffer = codec.getInputBuffer(index) ?: throw IOException("The encoder gave no input buffer")
            write(buffer)
        }
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, frames * 1_000_000L / fps, 0)
        frames++
        drain(endOfStream = false)
    }

    /** Flushes the encoder and finalises the MP4. */
    fun finish() {
        val index = dequeueInput()
        codec.queueInputBuffer(index, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(endOfStream = true)
        if (!muxing) throw IOException("The encoder produced no video")
        muxer.stop()
        muxing = false
    }

    override fun close() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxing) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun dequeueInput(): Int {
        var waits = 0
        while (true) {
            val index = codec.dequeueInputBuffer(10_000)
            if (index >= 0) return index
            drain(endOfStream = false)
            if (++waits > 1_000) throw IOException("The encoder stopped responding")
        }
    }

    private fun drain(endOfStream: Boolean) {
        var waits = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!endOfStream) return
                    if (++waits > 1_000) throw IOException("The encoder stopped responding")
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    if (muxing) throw IOException("The encoder changed format mid-stream")
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxing = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buffer != null && muxing) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    /** Writes the frame as BT.709 limited-range YUV 4:2:0 into whatever plane layout the encoder uses. */
    private fun write(image: Image) {
        val planes = image.planes
        val luma = planes[0]
        val lumaBuffer = luma.buffer
        for (r in 0 until height) {
            val base = r * width
            if (luma.pixelStride == 1) {
                for (c in 0 until width) row[c] = luma(pixels[base + c])
                lumaBuffer.position(r * luma.rowStride)
                lumaBuffer.put(row, 0, width)
            } else {
                for (c in 0 until width) lumaBuffer.put(r * luma.rowStride + c * luma.pixelStride, luma(pixels[base + c]))
            }
        }
        val u = planes[1]
        val v = planes[2]
        writeChroma(u.buffer, u.rowStride, u.pixelStride, v.buffer, v.rowStride, v.pixelStride)
    }

    /** Fallback for encoders without image access: their own planar or semi-planar layout. */
    private fun write(buffer: ByteBuffer) {
        buffer.clear()
        for (r in 0 until height) {
            val base = r * width
            for (c in 0 until width) row[c] = luma(pixels[base + c])
            buffer.position(r * width)
            buffer.put(row, 0, width)
        }
        val lumaSize = width * height
        @Suppress("DEPRECATION")
        val planar = runCatching {
            codec.inputFormat.getInteger(MediaFormat.KEY_COLOR_FORMAT) == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }.getOrDefault(false)
        val chroma = buffer.duplicate()
        if (planar) {
            chroma.position(lumaSize)
            val u = chroma.slice()
            chroma.position(lumaSize + lumaSize / 4)
            val v = chroma.slice()
            writeChroma(u, width / 2, 1, v, width / 2, 1)
        } else {
            chroma.position(lumaSize)
            val u = chroma.slice()
            chroma.position(lumaSize + 1)
            val v = chroma.slice()
            writeChroma(u, width, 2, v, width, 2)
        }
    }

    private fun writeChroma(u: ByteBuffer, uRow: Int, uStep: Int, v: ByteBuffer, vRow: Int, vStep: Int) {
        for (r in 0 until height / 2) {
            val top = 2 * r * width
            for (c in 0 until width / 2) {
                val i = top + 2 * c
                val a = pixels[i]
                val b = pixels[i + 1]
                val d = pixels[i + width]
                val e = pixels[i + width + 1]
                val red = (a shr 16 and 0xFF) + (b shr 16 and 0xFF) + (d shr 16 and 0xFF) + (e shr 16 and 0xFF)
                val green = (a shr 8 and 0xFF) + (b shr 8 and 0xFF) + (d shr 8 and 0xFF) + (e shr 8 and 0xFF)
                val blue = (a and 0xFF) + (b and 0xFF) + (d and 0xFF) + (e and 0xFF)
                u.put(r * uRow + c * uStep, (((-26 * red - 87 * green + 112 * blue + 512) shr 10) + 128).toByte())
                v.put(r * vRow + c * vStep, (((112 * red - 102 * green - 10 * blue + 512) shr 10) + 128).toByte())
            }
        }
    }

    private fun luma(color: Int): Byte {
        val r = color shr 16 and 0xFF
        val g = color shr 8 and 0xFF
        val b = color and 0xFF
        return (((47 * r + 157 * g + 16 * b + 128) shr 8) + 16).toByte()
    }

    companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}

/** Picks sizes and bitrates the device's H.264 encoders accept. */
object VideoSizes {
    private fun encoders(): List<MediaCodecInfo> =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.toList() }.getOrDefault(emptyList())
            .filter { info -> info.isEncoder && info.supportedTypes.any { it.equals(AvcEncoder.MIME, ignoreCase = true) } }
            .sortedBy { if (Build.VERSION.SDK_INT >= 29 && !it.isHardwareAccelerated) 1 else 0 }

    private fun capabilities(info: MediaCodecInfo): MediaCodecInfo.VideoCapabilities? =
        runCatching { info.getCapabilitiesForType(AvcEncoder.MIME).videoCapabilities }.getOrNull()

    /**
     * The largest size no bigger than [width]×[height], with about the same shape, that an
     * encoder supports at [fps]. Older phones can't encode 1080×1920 portrait video.
     */
    fun fit(width: Int, height: Int, fps: Int): Pair<Int, Int> {
        val all = encoders().mapNotNull(::capabilities)
        var scale = 1.0
        repeat(10) {
            val w = (width * scale).toInt() and 1.inv()
            val h = (height * scale).toInt() and 1.inv()
            for (caps in all) {
                val alignedW = w - w % maxOf(2, caps.widthAlignment)
                val alignedH = h - h % maxOf(2, caps.heightAlignment)
                val rateOk = runCatching { caps.areSizeAndRateSupported(alignedW, alignedH, fps.toDouble()) }.getOrDefault(true)
                if (caps.isSizeSupported(alignedW, alignedH) && rateOk) return alignedW to alignedH
            }
            scale *= 0.85
        }
        return (width / 2 and 1.inv()) to (height / 2 and 1.inv())
    }

    fun bitRate(width: Int, height: Int, fps: Int): Int {
        val wanted = (width.toLong() * height * fps * 0.14).toLong().coerceIn(1_000_000L, 40_000_000L).toInt()
        val range = encoders().firstNotNullOfOrNull(::capabilities)?.bitrateRange ?: return wanted
        return wanted.coerceIn(range.lower, range.upper)
    }

    fun encoderFor(width: Int, height: Int, format: MediaFormat): String? =
        runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).findEncoderForFormat(format) }.getOrNull()
            ?: encoders().firstOrNull { capabilities(it)?.isSizeSupported(width, height) == true }?.name
}
