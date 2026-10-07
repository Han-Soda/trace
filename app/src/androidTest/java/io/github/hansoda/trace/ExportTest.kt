package io.github.hansoda.trace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.ExifInterface
import android.media.MediaExtractor
import android.net.Uri
import android.media.MediaFormat
import android.Manifest
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.export.VideoExporter
import io.github.hansoda.trace.export.VideoSizes
import io.github.hansoda.trace.media.MediaLibrary
import io.github.hansoda.trace.media.PhotoStore
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.motion.Moment
import io.github.hansoda.trace.motion.MotionSettings
import io.github.hansoda.trace.motion.Planner
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.render.Overlay
import io.github.hansoda.trace.route.Route
import io.github.hansoda.trace.tiles.TileStore
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Encodes a short video on a real device and reads it back. */
@RunWith(AndroidJUnit4::class)
class ExportTest {
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

    private fun route(start: Long = 0): Route {
        val lats = DoubleArray(60) { 52.50 + it * 0.002 }
        val lons = DoubleArray(60) { 13.40 + kotlin.math.sin(it / 6.0) * 0.01 }
        val meters = DoubleArray(60)
        for (i in 1 until 60) meters[i] = meters[i - 1] + Geo.haversineMeters(lats[i - 1], lons[i - 1], lats[i], lons[i])
        return Route(
            DoubleArray(60) { Geo.x(lons[it]) }, DoubleArray(60) { Geo.y(lats[it]) },
            LongArray(60) { start + it * 60_000L }, ShortArray(60) { Timeline.NO_OFFSET }, meters, LongArray(60), 60,
        )
    }

    @Test
    fun exportsAPlayableVideo() = runBlocking {
        val (width, height) = VideoSizes.fit(360, 640, 30)
        val plan = Planner.plan(route(), MotionSettings(3.0, 30, width.toDouble() / height, 0.6))
        val look = Look(MapStyle.PAPER, false, 0xFFFF5A36.toInt(), 1f, true)
        val overlay = Overlay("Test", true, true, "Test", { _, _ -> "Mon 1 Jan" }, { "1.0 km" })
        val uri = VideoExporter(context, TileStore(context)).video(plan, width, height, look, overlay, "Trace test") {}
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            assertEquals(1, extractor.trackCount)
            val format = extractor.getTrackFormat(0)
            assertEquals(MediaFormat.MIMETYPE_VIDEO_AVC, format.getString(MediaFormat.KEY_MIME))
            assertEquals(width, format.getInteger(MediaFormat.KEY_WIDTH))
            assertTrue(format.getLong(MediaFormat.KEY_DURATION) > 2_500_000)
            extractor.release()
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun exportsATurningMap() = runBlocking {
        val (width, height) = VideoSizes.fit(360, 640, 30)
        val settings = MotionSettings(3.0, 30, width.toDouble() / height, 0.6, camera = CameraMode.HEADING, lag = 0.5)
        val plan = Planner.plan(route(), settings)
        assertTrue(plan.turns)
        val look = Look(MapStyle.STREETS, true, 0xFF2D7FF9.toInt(), 1f, false)
        val uri = VideoExporter(context, TileStore(context)).video(plan, width, height, look, Overlay.NONE, "Trace test") {}
        try {
            val extractor = MediaExtractor()
            extractor.setDataSource(context, uri, null)
            assertEquals(1, extractor.trackCount)
            assertTrue(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) > 2_500_000)
            extractor.release()
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }

    @Test
    fun showsAPhotoWhereItWasTaken() = runBlocking {
        // A photo taken ten minutes into a ride on 7 June 2025, as a camera would save it.
        val start = 1_749_286_800_000L
        val file = File(context.cacheDir, "test-photo.jpg")
        val picture = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        Canvas(picture).drawColor(Color.rgb(40, 120, 200))
        file.outputStream().use { picture.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        // Cameras write the local time; older Androids don't keep the offset, so use the phone's zone.
        val taken = Instant.ofEpochMilli(start + 10 * 60_000L).atZone(ZoneId.systemDefault())
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(taken))
            setAttribute("OffsetTimeOriginal", if (taken.offset.totalSeconds == 0) "+00:00" else taken.offset.id)
            saveAttributes()
        }
        val library = MediaLibrary(context)
        val added = library.add(listOf(Uri.fromFile(file)), emptyList()) { _, _ -> }
        assertEquals(1, added.items.size)
        val photo = added.items.single()
        assertEquals(start + 10 * 60_000L, photo.time)
        try {
            val (width, height) = VideoSizes.fit(360, 640, 30)
            val settings = MotionSettings(4.0, 30, width.toDouble() / height, 0.6, moments = listOf(Moment(photo.id, photo.time, 1.5)))
            val plan = Planner.plan(route(start), settings)
            assertEquals(10, plan.moments.single().point)
            val look = Look(MapStyle.PAPER, false, 0xFFFF5A36.toInt(), 1f, false)
            val photos = PhotoStore(library)
            val uri = VideoExporter(context, TileStore(context), photos.now).video(plan, width, height, look, Overlay.NONE, "Trace test") {}
            try {
                val extractor = MediaExtractor()
                extractor.setDataSource(context, uri, null)
                assertEquals(1, extractor.trackCount)
                assertTrue(extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) > 3_500_000)
                extractor.release()
            } finally {
                context.contentResolver.delete(uri, null, null)
            }
        } finally {
            library.remove(photo.id)
            file.delete()
        }
    }

    @Test
    fun savesAnImage() = runBlocking {
        val plan = Planner.plan(route(), MotionSettings(3.0, 30, 1.0, 0.6))
        val look = Look(MapStyle.INK, false, 0xFF2D7FF9.toInt(), 1f, false)
        val uri = VideoExporter(context, TileStore(context)).image(plan, 540, 540, look, Overlay.NONE, "Trace test") {}
        try {
            context.contentResolver.openInputStream(uri).use { assertTrue((it?.available() ?: 0) >= 0) }
        } finally {
            context.contentResolver.delete(uri, null, null)
        }
    }
}
