package io.github.hansoda.trace

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.export.AacEncoder
import io.github.hansoda.trace.export.AvcEncoder
import io.github.hansoda.trace.export.MediaSaver
import io.github.hansoda.trace.export.VideoExporter
import io.github.hansoda.trace.export.VideoSizes
import io.github.hansoda.trace.media.MediaFinder
import io.github.hansoda.trace.media.MediaLibrary
import io.github.hansoda.trace.media.PhotoStore
import io.github.hansoda.trace.media.Sound
import io.github.hansoda.trace.motion.Moment
import io.github.hansoda.trace.motion.MotionSettings
import io.github.hansoda.trace.motion.Planner
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.render.Overlay
import io.github.hansoda.trace.render.PhotoStyle
import io.github.hansoda.trace.route.Route
import io.github.hansoda.trace.tiles.TileStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Clips from real videos on a real device: their frames, their sound, and finding photos by date. */
@RunWith(AndroidJUnit4::class)
class ClipTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    /** Android 8 and 9 need both: without read access, shared storage isn't mounted for the app. */
    @Before
    fun allowSavingOnOldAndroid() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.grantRuntimePermission(context.packageName, Manifest.permission.READ_EXTERNAL_STORAGE)
            automation.grantRuntimePermission(context.packageName, Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    /** A video in the gallery whose picture gets redder frame by frame, with a steady tone when [tone]. */
    private fun video(seconds: Double, fps: Int, tone: Boolean): Uri {
        val (width, height) = VideoSizes.fit(320, 240, fps)
        val frames = (seconds * fps).toInt()
        val file = File(context.cacheDir, "clip-source.mp4")
        file.delete()
        val sound = if (!tone) {
            null
        } else {
            AacEncoder.encode(ShortArray((seconds * Sound.RATE).toInt() * Sound.CHANNELS) { k -> (8000 * sin(2 * PI * 440 * (k / 2) / Sound.RATE)).toInt().toShort() })
        }
        AvcEncoder(width, height, fps, VideoSizes.bitRate(width, height, fps), file, sound).use { encoder ->
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            for (f in 0 until frames) {
                Canvas(bitmap).drawColor(Color.rgb((f * 2) % 256, 80, 160))
                encoder.encode(bitmap)
            }
            encoder.finish()
        }
        return MediaSaver.saveVideo(context, file, "Trace clip test ${System.nanoTime()}").also { file.delete() }
    }

    private fun route(start: Long): Route {
        val lats = DoubleArray(60) { 52.50 + it * 0.002 }
        val lons = DoubleArray(60) { 13.40 + sin(it / 6.0) * 0.01 }
        val meters = DoubleArray(60)
        for (i in 1 until 60) meters[i] = meters[i - 1] + Geo.haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(
            DoubleArray(60) { Geo.x(lons[it]) }, DoubleArray(60) { Geo.y(lats[it]) },
            LongArray(60) { start + it * 60_000L }, ShortArray(60) { Timeline.NO_OFFSET }, meters, LongArray(60), 60,
        )
    }

    @Test
    fun keepsEveryFrameOfThePartChosen() = runBlocking {
        val source = video(4.0, 30, tone = false)
        val library = MediaLibrary(context)
        try {
            val added = library.add(listOf(source), emptyList()) { _, _ -> }
            assertEquals("undated ${added.undated}, unreadable ${added.unreadable}", 1, added.items.size)
            val clip = added.items.single()
            try {
                // By default a few seconds from just after the start, at the video's own pace.
                assertTrue("${clip.fps} fps", clip.fps in 25.0..30.0)
                assertTrue("starts at ${clip.startMs}", clip.startMs in 380..400)
                assertTrue("${clip.frames} frames", clip.frames.toDouble() in 3.4 * clip.fps..3.65 * clip.fps)
                assertFalse(clip.audio)
                assertTrue(clip.canTrim)
                assertTrue(library.thumbnailFile(clip.id).length() > 0)
                // Another part: a second and a half from the first second.
                val trimmed = library.trim(clip, 1_000, 1_500) {}
                assertEquals(1_000L, trimmed.startMs)
                val expected = kotlin.math.ceil(1.5 * trimmed.fps).toInt()
                assertTrue("${trimmed.frames} frames at ${trimmed.fps}", trimmed.frames in expected - 2..expected)
                // One frame after another: each redder than the one before.
                val first = BitmapFactory.decodeFile(library.frameFile(trimmed.id, 0).path)
                val last = BitmapFactory.decodeFile(library.frameFile(trimmed.id, trimmed.frames - 1).path)
                val redder = Color.red(last.getPixel(10, 10)) - Color.red(first.getPixel(10, 10))
                assertTrue("redder by $redder", redder > 40)
            } finally {
                library.remove(clip)
            }
        } finally {
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test
    fun exportsClipsWithTheirSound() = runBlocking {
        val source = video(3.0, 30, tone = true)
        val library = MediaLibrary(context)
        val photos = PhotoStore(library)
        try {
            val clip = library.add(listOf(source), emptyList()) { _, _ -> }.items.single()
            try {
                assertTrue(clip.audio)
                val sound = Sound.read(library.soundFile(clip.id))!!
                // Stereo at 48 kHz for the part kept, give or take a little, and not silent.
                assertEquals(clip.seconds * Sound.RATE * Sound.CHANNELS, sound.size.toDouble(), Sound.RATE * Sound.CHANNELS * 0.15)
                assertTrue(sound.any { abs(it.toInt()) > 2_000 })

                // A video with the clip on the route has a sound track, unless sound is off.
                val (width, height) = VideoSizes.fit(360, 640, 30)
                val moments = listOf(Moment(clip.id, clip.shownTime, clip.seconds + 0.8, clip.frames, clip.fps))
                val plan = Planner.plan(route(clip.shownTime - 10 * 60_000L), MotionSettings(6.0, 30, width.toDouble() / height, 0.6, moments = moments))
                assertEquals(1, plan.moments.size)
                val look = Look(MapStyle.PAPER, false, 0xFFFF5A36.toInt(), 1f, false, PhotoStyle.CORNER)
                val exporter = VideoExporter(context, TileStore(context), photos.now)
                for (withSound in listOf(true, false)) {
                    val uri = exporter.video(plan, width, height, look, Overlay.NONE, "Trace test", if (withSound) photos.sounds else null) {}
                    try {
                        val extractor = MediaExtractor()
                        extractor.setDataSource(context, uri, null)
                        val kinds = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                        extractor.release()
                        assertTrue("$kinds", kinds.any { it.startsWith("video/") })
                        assertEquals("$kinds", withSound, kinds.any { it.startsWith("audio/") })
                    } finally {
                        context.contentResolver.delete(uri, null, null)
                    }
                }
            } finally {
                library.remove(clip)
            }
        } finally {
            context.contentResolver.delete(source, null, null)
        }
    }

    @Test
    fun findsPhotosFromTheChosenDays() {
        val taken = 1_749_286_800_000L
        val photo = galleryPhoto(taken)
        try {
            val id = ContentUris.parseId(photo)
            val finder = MediaFinder(context)
            // Wide enough for any time zone the gallery reads the photo's clock in.
            val hours = 3_600_000L
            val sameDay = finder.find(listOf(taken - 26 * hours..taken + 26 * hours))
            val stored = context.contentResolver.query(photo, arrayOf("datetaken", "date_modified"), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) "taken ${cursor.getString(0)}, modified ${cursor.getString(1)}" else "not in the gallery"
            }
            assertTrue("$stored; found ${sameDay.size}", sameDay.any { ContentUris.parseId(it.uri) == id && !it.video })
            val daysLater = finder.find(listOf(taken + 72 * hours..taken + 96 * hours))
            assertFalse(daysLater.any { ContentUris.parseId(it.uri) == id })
        } finally {
            context.contentResolver.delete(photo, null, null)
        }
    }

    /** A photo in the gallery taken at [taken], as a camera app would leave it. */
    private fun galleryPhoto(taken: Long): Uri {
        val temporary = File(context.cacheDir, "gallery-photo.jpg")
        val picture = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        Canvas(picture).drawColor(Color.rgb(200, 120, 40))
        temporary.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(temporary.path).apply {
            // With its time zone, as phones' cameras write it, so the gallery knows when it was.
            val local = Instant.ofEpochMilli(taken).atZone(ZoneId.systemDefault())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(local))
            setAttribute("OffsetTimeOriginal", if (local.offset.totalSeconds == 0) "+00:00" else local.offset.id)
            saveAttributes()
        }
        val bytes = temporary.readBytes()
        temporary.delete()
        val resolver = context.contentResolver
        val name = "trace-test-${System.nanoTime()}.jpg"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put("datetaken", taken)
        }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/TraceTest")
            values.put(MediaStore.MediaColumns.IS_PENDING, 1)
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            uri
        } else {
            @Suppress("DEPRECATION")
            val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "TraceTest").apply { mkdirs() }
            val file = File(folder, name)
            file.writeBytes(bytes)
            @Suppress("DEPRECATION")
            values.put(MediaStore.MediaColumns.DATA, file.path)
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
        }
    }
}
