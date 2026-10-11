package io.github.hansoda.trace

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hansoda.trace.data.TimelineRepository
import java.io.File
import java.time.Instant
import java.time.ZoneOffset
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
        val format = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
        val zone = ZoneOffset.ofHours(2)
        var time = Instant.parse("2023-06-01T06:00:00Z").toEpochMilli()
        var points = 0
        file.bufferedWriter().use { out ->
            out.write("""{"semanticSegments":[""")
            for (day in 0 until DAYS) {
                for (trip in 0 until TRIPS) {
                    if (day > 0 || trip > 0) out.write(",")
                    val start = time
                    out.write("""{"startTime":"${format.format(Instant.ofEpochMilli(start).atOffset(zone))}",""")
                    out.write(""""endTime":"${format.format(Instant.ofEpochMilli(start + POINTS * 60_000L).atOffset(zone))}","timelinePath":[""")
                    for (k in 0 until POINTS) {
                        if (k > 0) out.write(",")
                        val lat = 52.45 + ((day * 7 + trip * 13 + k) % 200) * 0.0005
                        val lon = 13.30 + ((day * 11 + trip * 5 + k * 3) % 300) * 0.0005
                        val at = format.format(Instant.ofEpochMilli(time).atOffset(zone))
                        out.write("""{"point":"${"%.7f".format(Locale.ROOT, lat)}°, ${"%.7f".format(Locale.ROOT, lon)}°","time":"$at"}""")
                        time += 60_000L
                        points++
                    }
                    out.write("]}")
                    time += 30 * 60_000L
                }
                time = Instant.parse("2023-06-01T06:00:00Z").toEpochMilli() + (day + 1) * 86_400_000L
            }
            out.write("]}")
        }
        return points
    }

    private companion object {
        const val DAYS = 730
        const val TRIPS = 10
        const val POINTS = 50
    }
}
