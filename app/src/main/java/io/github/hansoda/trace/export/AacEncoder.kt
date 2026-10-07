package io.github.hansoda.trace.export

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import io.github.hansoda.trace.media.Sound
import java.io.IOException
import java.nio.ByteOrder

/** An encoded track, ready for the muxer: its format and every sample in order. */
class EncodedTrack(val format: MediaFormat, val samples: List<EncodedSample>)

class EncodedSample(val data: ByteArray, val timeUs: Long, val flags: Int)

/** Encodes [Sound]'s 48 kHz stereo samples into AAC with the platform encoder. */
object AacEncoder {
    private const val BIT_RATE = 160_000
    private const val TIMEOUT_US = 10_000L

    fun encode(pcm: ShortArray): EncodedTrack {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, Sound.RATE, Sound.CHANNELS).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val samples = ArrayList<EncodedSample>()
            var output: MediaFormat? = null
            val info = MediaCodec.BufferInfo()
            val frames = pcm.size / Sound.CHANNELS
            var written = 0
            var inputDone = false
            var idle = 0
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index) ?: throw IOException("No input buffer")
                        buffer.clear()
                        val room = buffer.remaining() / (2 * Sound.CHANNELS)
                        val count = minOf(room, frames - written)
                        val timeUs = written * 1_000_000L / Sound.RATE
                        if (count <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(ByteOrder.nativeOrder()).asShortBuffer().put(pcm, written * Sound.CHANNELS, count * Sound.CHANNELS)
                            codec.queueInputBuffer(index, 0, count * 2 * Sound.CHANNELS, timeUs, 0)
                            written += count
                        }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> output = codec.outputFormat
                    index >= 0 -> {
                        idle = 0
                        val buffer = codec.getOutputBuffer(index)
                        // The setup data travels in the format; the muxer wants only the sound.
                        if (buffer != null && info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val data = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(data)
                            samples += EncodedSample(data, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false)
                        if (ended) break
                    }
                    else -> if (++idle > 1_000) throw IOException("The sound encoder stopped")
                }
            }
            return EncodedTrack(output ?: throw IOException("The sound encoder gave no format"), samples)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
    }
}
