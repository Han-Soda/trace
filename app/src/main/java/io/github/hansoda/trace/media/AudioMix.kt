package io.github.hansoda.trace.media

import io.github.hansoda.trace.motion.Plan
import kotlin.math.max
import kotlin.math.min

/** A clip's sound, as [Sound] keeps it, or null when it has none. */
fun interface SoundSource {
    fun sound(id: String): ShortArray?
}

/** The sound of a video: each clip's own sound while it plays, quiet in between. */
object AudioMix {
    /** Seconds over which a clip's sound comes in, and goes. */
    private const val FADE_IN = 0.02
    private const val FADE_OUT = 0.15

    /**
     * Interleaved stereo samples at [Sound.RATE] for all of [plan], or null when no clip on it
     * has sound.
     */
    fun mix(plan: Plan, sounds: SoundSource): ShortArray? {
        val total = (plan.frameCount.toLong() * Sound.RATE / plan.fps).toInt()
        var out: ShortArray? = null
        for (shown in plan.moments) {
            val moment = shown.moment
            if (moment.frames <= 1 || moment.fps <= 0) continue
            val sound = sounds.sound(moment.id) ?: continue
            // The clip starts once its picture is up and plays at its own pace, as it's drawn.
            val start = ((shown.startFrame + shown.openFrames) / plan.fps * Sound.RATE).toInt()
            val shownUntil = ((shown.endFrame + 1).toDouble() / plan.fps * Sound.RATE).toInt()
            val clipFrames = (moment.frames / moment.fps * Sound.RATE).toInt()
            val length = minOf(clipFrames, sound.size / Sound.CHANNELS, shownUntil - start, total - start)
            if (length <= 0) continue
            val into = out ?: ShortArray(total * Sound.CHANNELS).also { out = it }
            val fadeIn = max(1, (FADE_IN * Sound.RATE).toInt())
            val fadeOut = max(1, min((FADE_OUT * Sound.RATE).toInt(), length / 2))
            for (k in 0 until length) {
                val gain = min(1.0, min(k.toDouble() / fadeIn, (length - k).toDouble() / fadeOut))
                for (c in 0 until Sound.CHANNELS) {
                    val at = (start + k) * Sound.CHANNELS + c
                    val mixed = into[at] + sound[k * Sound.CHANNELS + c] * gain
                    into[at] = mixed.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                }
            }
        }
        return out
    }
}
