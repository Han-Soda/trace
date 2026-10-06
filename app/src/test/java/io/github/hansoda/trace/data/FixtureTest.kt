package io.github.hansoda.trace.data

import io.github.hansoda.trace.route.RouteBuilder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small real-shaped exports in the formats people send us. */
class FixtureTest {
    private fun load(name: String): Timeline {
        val builder = TimelineBuilder()
        val stream = javaClass.getResourceAsStream("/fixtures/$name.json") ?: error("missing $name")
        stream.reader().use { TimelineParser(builder).parse(it) }
        return builder.build()
    }

    @Test
    fun parsesEveryFixture() {
        assertEquals(11, load("android-ios-sample").size)
        assertEquals(3, load("takeout-sample").size)
        // Raw signals fill the weeks the processed segments don't cover.
        assertEquals(4, load("semantic-and-raw-ranges").size)
    }

    @Test
    fun dropsTheOutlier() {
        val timeline = load("outlier-sample")
        assertEquals(3, timeline.size)
        val range = RouteBuilder.build(timeline, Long.MIN_VALUE, Long.MAX_VALUE)
        assertEquals(2, range.size)
        assertTrue(range.totalMeters < 1_000)
    }

    @Test
    fun readsMinuteOffsets() {
        val timeline = load("offset-path-sample")
        assertEquals(2, timeline.size)
        assertEquals(java.time.Instant.parse("2026-01-01T00:15:00Z").toEpochMilli(), timeline.times[0])
        assertEquals(java.time.Instant.parse("2026-01-01T01:00:00Z").toEpochMilli(), timeline.times[1])
    }
}
