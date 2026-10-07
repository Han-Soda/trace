package io.github.hansoda.trace.media

import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.motion.Moment
import io.github.hansoda.trace.motion.MotionSettings
import io.github.hansoda.trace.motion.Planner
import io.github.hansoda.trace.route.Route
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioMixTest {
    private fun walk(): Route {
        val n = 40
        val lats = DoubleArray(n) { 52.50 + it * 0.001 }
        val meters = DoubleArray(n)
        for (i in 1 until n) meters[i] = meters[i - 1] + Geo.haversineMeters(lats[i - 1], 13.4, lats[i], 13.4)
        return Route(
            DoubleArray(n) { Geo.x(13.4) }, DoubleArray(n) { Geo.y(lats[it]) }, LongArray(n) { it * 60_000L },
            ShortArray(n) { Timeline.NO_OFFSET }, meters, LongArray(n), n,
        )
    }

    @Test
    fun clipsPlayTheirSoundWhileTheyreUp() {
        val route = walk()
        // A three-second clip with a steady tone, and a photo, which has no sound.
        val moments = listOf(Moment("clip", route.times[10], 3.8, frames = 90, fps = 30.0), Moment("photo", route.times[30], 2.0))
        val plan = Planner.plan(route, MotionSettings(20.0, 30, 9.0 / 16, 0.6, 0.1, CameraMode.TRACK, moments = moments))
        val tone = ShortArray(3 * Sound.RATE * Sound.CHANNELS) { 8000 }
        val mix = AudioMix.mix(plan) { id -> if (id == "clip") tone else null }!!
        assertEquals(plan.frameCount * Sound.RATE / plan.fps * Sound.CHANNELS, mix.size)

        val clip = plan.moments.first { it.moment.id == "clip" }
        val start = ((clip.startFrame + clip.openFrames) / plan.fps * Sound.RATE).toInt()
        fun level(second: Double) = abs(mix[(second * Sound.RATE).toInt() * Sound.CHANNELS].toInt())
        val begins = start.toDouble() / Sound.RATE
        // Quiet until the clip is up, its sound for its three seconds, quiet again after.
        assertEquals(0, level(begins - 0.1))
        assertEquals(8000, level(begins + 0.5))
        assertEquals(8000, level(begins + 2.5))
        assertEquals(0, level(begins + 3.2))
        // It fades out rather than stopping with a click.
        assertTrue(level(begins + 2.95) in 1..7999)
        // Nothing for the photo.
        val photo = plan.moments.first { it.moment.id == "photo" }
        assertEquals(0, level((photo.startFrame + photo.endFrame) / 2.0 / plan.fps))
    }

    @Test
    fun noSoundNoTrack() {
        val route = walk()
        val plan = Planner.plan(route, MotionSettings(10.0, 30, 1.0, 0.6, moments = listOf(Moment("clip", route.times[10], 2.8, 60, 30.0))))
        assertNull(AudioMix.mix(plan) { null })
    }

    @Test
    fun resamplesToTheVideoRate() {
        val from = 44_100
        val left = ShortArray(from) { (it % 1000).toShort() }
        val right = ShortArray(from) { (-(it % 1000)).toShort() }
        val out = Sound.resample(left, right, from, Sound.RATE)
        assertEquals(Sound.RATE * Sound.CHANNELS, out.size)
        // The same moment in the sound lands at the same time.
        val at = (0.5 * Sound.RATE).toInt()
        assertEquals(left[(0.5 * from).toInt()].toDouble(), out[at * 2].toDouble(), 2.0)
        assertEquals(right[(0.5 * from).toInt()].toDouble(), out[at * 2 + 1].toDouble(), 2.0)
    }
}
