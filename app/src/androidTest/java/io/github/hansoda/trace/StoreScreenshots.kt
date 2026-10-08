package io.github.hansoda.trace

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.hansoda.trace.data.TimelineRepository
import io.github.hansoda.trace.export.MediaSaver
import io.github.hansoda.trace.export.VideoExporter
import io.github.hansoda.trace.media.MediaItem
import io.github.hansoda.trace.media.MediaLibrary
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.render.PhotoStyle
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.settings.AppLanguage
import io.github.hansoda.trace.settings.SettingsStore
import io.github.hansoda.trace.settings.TraceSettings
import io.github.hansoda.trace.settings.Units
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Screenshots for the Play listing, taken from the app itself with a made-up bike ride through
 * Chicago, in English and Russian: the editor's tabs, and frames of the video it makes at
 * 1080 × 1920. They land in Pictures/Trace. Runs only when asked for, with the instrumentation
 * argument screenshots=true, as the store-screenshots job in CI does.
 */
@RunWith(AndroidJUnit4::class)
class StoreScreenshots {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(instrumentation)

    @Before
    fun onlyWhenAsked() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("screenshots") == "true")
    }

    @Test
    fun takeScreenshots() = runBlocking {
        cleanStatusBar()
        val fixes = ride()
        val file = File(context.cacheDir, "store-ride.json").apply { writeText(timelineJson(fixes)) }
        TimelineRepository(context).import(listOf(Uri.fromFile(file))) {}
        val library = MediaLibrary(context)
        library.load().forEach(library::remove)
        // Coffee in Grant Park at the first stop, and a cat met in Lincoln Park near the end.
        val coffee = photo(library, "coffee", fixes.firstStop + 20 * 60_000L)
        val cat = photo(library, "cat", fixes.last().time - 7 * 60_000L)
        val day = LocalDate.of(2025, 6, 7).toEpochDay()
        for (language in AppLanguage.entries) {
            val russian = language == AppLanguage.RUSSIAN
            AppLanguage.choose(context, language)
            library.save(
                listOf(
                    coffee.copy(place = if (russian) "Грант-парк, Чикаго" else "Grant Park, Chicago", placeLanguage = language.tag),
                    cat.copy(place = if (russian) "Линкольн-парк, Чикаго" else "Lincoln Park, Chicago", placeLanguage = language.tag),
                ),
            )
            SettingsStore(context).save(
                TraceSettings(
                    // The ride, from just before it set off to just after it ended.
                    days = DaySelection.range(day, day).withTimes(8 * 60 + 45, 12 * 60 + 15),
                    style = MapStyle.STREETS,
                    photoStyle = PhotoStyle.POLAROID,
                    title = if (russian) "Чикаго на велосипеде" else "Chicago by bike",
                    units = Units.KILOMETERS,
                ),
            )
            shoot(language.tag, coffee.id, cat.id)
        }
    }

    private suspend fun shoot(tag: String, coffee: String, cat: String) {
        val text = AppLanguage.apply(context)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            check(device.wait(Until.hasObject(By.clazz(SLIDER)), 60_000)) { "The editor didn't open" }
            lateinit var model: TraceViewModel
            scenario.onActivity { model = ViewModelProvider(it)[TraceViewModel::class.java] }
            val plan = withTimeout(60_000) { model.preview.first { it != null } }!!
            val seconds = model.settings.value.durationSeconds
            val coffeeFrame = plan.middleOf(coffee)
            val catFrame = plan.middleOf(cat)

            seek(plan, coffeeFrame, seconds)
            screenshot("$tag-2-editor")
            // The calendar, with the ride's exact times.
            (device.findObject(By.text(text.getString(R.string.pick_dates))) ?: error("No date picker")).click()
            check(device.wait(Until.hasObject(By.text(text.getString(R.string.exact_times))), 10_000)) { "The calendar didn't open" }
            SystemClock.sleep(1_500)
            screenshot("$tag-8-dates")
            device.pressBack()
            device.waitForIdle()
            SystemClock.sleep(1_000)
            tab(text.getString(R.string.tab_photos))
            seek(plan, catFrame, seconds)
            screenshot("$tag-4-photos")
            tab(text.getString(R.string.tab_look))
            seek(plan, (coffeeFrame + catFrame) / 2, seconds)
            screenshot("$tag-5-look")
            tab(text.getString(R.string.tab_camera))
            seek(plan, coffeeFrame / 2, seconds)
            screenshot("$tag-7-camera")

            // Frames of the video itself, as the export draws them.
            val exporter = VideoExporter(context, model.tiles, model.photos.now)
            val look = model.look.value
            val overlay = model.overlay.value
            exporter.image(plan, WIDTH, HEIGHT, look, overlay, "$tag-1-video", coffeeFrame) {}
            exporter.image(plan, WIDTH, HEIGHT, look, overlay, "$tag-3-route") {}
            exporter.image(plan, WIDTH, HEIGHT, look, overlay, "$tag-6-cat", catFrame) {}
        }
    }

    /** The middle of the time the photo [id] is up, when it's shown in full. */
    private fun Plan.middleOf(id: String): Int {
        val moment = moments.first { it.moment.id == id }
        return (moment.startFrame + moment.endFrame) / 2
    }

    /** Moves the preview to [frame], the way dragging its slider does, and lets the map load. */
    private fun seek(plan: Plan, frame: Int, seconds: Int) {
        device.waitForIdle()
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: error("No window")
        val sliders = ArrayList<AccessibilityNodeInfo>()
        collect(root) { if (it.className?.toString() == SLIDER) sliders += it }
        // The preview's slider is the one at the top; tabs can have their own further down.
        val slider = sliders.minByOrNull { node -> Rect().also(node::getBoundsInScreen).top } ?: error("No slider")
        val progress = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, ((frame + 0.5f) / plan.fps / seconds).coerceIn(0f, 1f))
        }
        check(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, progress)) { "Couldn't move the preview" }
        SystemClock.sleep(SETTLE_MS)
    }

    private fun collect(node: AccessibilityNodeInfo, found: (AccessibilityNodeInfo) -> Unit) {
        found(node)
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, found) }
    }

    private fun tab(name: String) {
        (device.findObject(By.text(name)) ?: error("No tab $name")).click()
        device.waitForIdle()
    }

    /** The whole screen, as a PNG without an alpha channel, which Google Play turns away. */
    private fun screenshot(name: String) {
        val screen = instrumentation.uiAutomation.takeScreenshot() ?: error("Couldn't take $name")
        val opaque = screen.copy(Bitmap.Config.ARGB_8888, true).apply { setHasAlpha(false) }
        MediaSaver.saveImage(context, name) { out -> opaque.compress(Bitmap.CompressFormat.PNG, 100, out) }
        opaque.recycle()
        screen.recycle()
    }

    /** A full battery, full signal and the same time on every screenshot, and no notifications. */
    private fun cleanStatusBar() {
        shell("settings put global sysui_demo_allowed 1")
        for (command in listOf(
            "enter",
            "clock -e hhmm 1041",
            "battery -e level 100 -e plugged false",
            "network -e wifi show -e level 4 -e fully true",
            "network -e mobile hide",
            "notifications -e visible false",
        )) {
            shell("am broadcast -a com.android.systemui.demo -e command $command")
        }
    }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).use { it.readBytes() }
    }

    /** Adds one of the test's photos, dated [time] as a camera in Chicago would. */
    private suspend fun photo(library: MediaLibrary, name: String, time: Long): MediaItem {
        val file = File(context.cacheDir, "$name.jpg")
        instrumentation.context.assets.open("store/$name.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
        ExifInterface(file.path).apply {
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss").format(Instant.ofEpochMilli(time).atOffset(CHICAGO)))
            setAttribute("OffsetTimeOriginal", "-05:00")
            saveAttributes()
        }
        return library.add(listOf(Uri.fromFile(file)), emptyList()) { _, _ -> }.items.single().also { file.delete() }
    }

    private class Fix(val time: Long, val lat: Double, val lon: Double)

    private val List<Fix>.firstStop: Long get() = this[STOP_AT * POINTS_PER_LEG].time

    /** About 12 km through Chicago on 7 June 2025: coffee in Grant Park, lunch in Maggie Daley Park. */
    private fun ride(): List<Fix> {
        val fixes = ArrayList<Fix>()
        var time = Instant.parse("2025-06-07T14:00:00Z").toEpochMilli()
        for (leg in 0 until WAYPOINTS.size - 1) {
            val (lat0, lon0) = WAYPOINTS[leg]
            val (lat1, lon1) = WAYPOINTS[leg + 1]
            for (k in 0 until POINTS_PER_LEG) {
                val f = k.toDouble() / POINTS_PER_LEG
                val bend = sin(f * PI) * 0.0008 * (if (leg % 2 == 0) 1 else -1)
                fixes += Fix(time, lat0 + (lat1 - lat0) * f + bend, lon0 + (lon1 - lon0) * f)
                time += 45_000
            }
            if (leg + 1 == STOP_AT || leg + 1 == LUNCH_AT) {
                repeat(8) {
                    fixes += Fix(time, lat1, lon1)
                    time += 300_000
                }
            }
        }
        val (lat, lon) = WAYPOINTS.last()
        fixes += Fix(time, lat, lon)
        return fixes
    }

    /** [fixes] as the phone's own Timeline export writes them. */
    private fun timelineJson(fixes: List<Fix>): String {
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        fun time(at: Long) = format.format(Instant.ofEpochMilli(at).atOffset(CHICAGO))
        val path = fixes.joinToString(",") { fix ->
            """{"point":"${"%.7f".format(Locale.ROOT, fix.lat)}°, ${"%.7f".format(Locale.ROOT, fix.lon)}°","time":"${time(fix.time)}"}"""
        }
        return """{"semanticSegments":[{"startTime":"${time(fixes.first().time)}","endTime":"${time(fixes.last().time)}","timelinePath":[$path]}]}"""
    }

    private companion object {
        const val SLIDER = "android.widget.SeekBar"
        const val WIDTH = 1080
        const val HEIGHT = 1920

        /** Long enough for the map tiles and photos to come in after a change. */
        const val SETTLE_MS = 8_000L
        const val POINTS_PER_LEG = 12

        /** The waypoints, after which the ride stops for coffee and for lunch. */
        const val STOP_AT = 5
        const val LUNCH_AT = 8
        val CHICAGO: ZoneOffset = ZoneOffset.ofHours(-5)

        /** Wicker Park, the Loop, Grant Park, Streeterville, the Gold Coast, Lincoln Park. */
        val WAYPOINTS = listOf(
            41.908 to -87.677, 41.903 to -87.662, 41.896 to -87.648, 41.889 to -87.636, 41.881 to -87.629,
            41.876 to -87.623, 41.8725 to -87.6205, 41.879 to -87.619, 41.885 to -87.618, 41.896 to -87.621,
            41.906 to -87.626, 41.915 to -87.632, 41.922 to -87.636,
        )
    }
}
