package io.github.hansoda.trace

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hansoda.trace.data.TimelineRepository
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Two years of the phone's own export, about 30 MB, imports in reasonable time without running out of memory. */
@RunWith(AndroidJUnit4::class)
class LargeImportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val file = File(context.cacheDir, "large-timeline.json")

    @After
    fun cleanUp() {
        file.delete()
        TimelineRepository(context).clear()
    }

    @Test
    fun importsTwoYears() = runBlocking {
        val points = write()
        val started = SystemClock.elapsedRealtime()
        val (timeline, info) = TimelineRepository(context).import(listOf(Uri.fromFile(file))) {}
        val seconds = (SystemClock.elapsedRealtime() - started) / 1000.0
        assertEquals(points, info.points)
        assertEquals(points, timeline.size)
        assertTrue("Took $seconds s", seconds < 120)
        // And it reads back what it saved.
        assertEquals(points, TimelineRepository(context).load()?.size)
    }

    /** Writes the export: every day ten trips of 50 points each across Berlin, and returns the points. */
    private fun write(): Int {
        // Formatting every point with a formatter takes minutes on an old emulator; the grid of
        // places and the times are simple enough to write by hand.
        val lats = Array(200) { "%.4f".format(Locale.ROOT, 52.45 + it * 0.0005) }
        val lons = Array(300) { "%.4f".format(Locale.ROOT, 13.30 + it * 0.0005) }
        val days = DateTimeFormatter.ISO_LOCAL_DATE
        val first = LocalDate.of(2023, 6, 1)
        fun stamp(date: String, minute: Int) = "${date}T%02d:%02d:00.000+02:00".format(Locale.ROOT, minute / 60, minute % 60)
        var points = 0
        file.bufferedWriter(bufferSize = 1 shl 16).use { out ->
            out.write("""{"semanticSegments":[""")
            for (day in 0 until DAYS) {
                val date = days.format(first.plusDays(day.toLong()))
                var minute = 6 * 60
                for (trip in 0 until TRIPS) {
                    if (day > 0 || trip > 0) out.write(",")
                    out.write("""{"startTime":"${stamp(date, minute)}","endTime":"${stamp(date, minute + POINTS)}","timelinePath":[""")
                    for (k in 0 until POINTS) {
                        if (k > 0) out.write(",")
                        out.write("""{"point":"""")
                        out.write(lats[(day * 7 + trip * 13 + k) % 200])
                        out.write("°, ")
                        out.write(lons[(day * 11 + trip * 5 + k * 3) % 300])
                        out.write("""°","time":"""")
                        out.write(date)
                        out.write("T")
                        out.write(two(minute / 60))
                        out.write(":")
                        out.write(two(minute % 60))
                        out.write(""":00.000+02:00"}""")
                        minute++
                        points++
                    }
                    out.write("]}")
                    minute += 30
                }
            }
            out.write("]}")
        }
        return points
    }

    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    private companion object {
        const val DAYS = 730
        const val TRIPS = 10
        const val POINTS = 50
    }
}
