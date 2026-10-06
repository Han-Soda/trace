package io.github.hansoda.trace.data

import java.io.File
import java.time.OffsetDateTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TimelineParserTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun parse(json: String): Timeline {
        val builder = TimelineBuilder()
        TimelineParser(builder).parse(json.reader())
        return builder.build()
    }

    private fun millis(text: String) = OffsetDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun readsOnDeviceExport() {
        val timeline = parse(
            """
            {
              "semanticSegments": [
                {
                  "startTime": "2024-03-01T08:00:00.000+09:00",
                  "endTime": "2024-03-01T09:00:00.000+09:00",
                  "startTimeTimezoneUtcOffsetMinutes": 540,
                  "visit": {
                    "hierarchyLevel": 0,
                    "probability": 0.9,
                    "topCandidate": {
                      "placeId": "ChIJ",
                      "semanticType": "HOME",
                      "probability": 0.8,
                      "placeLocation": { "latLng": "35.6812°, 139.7671°" }
                    }
                  }
                },
                {
                  "startTime": "2024-03-01T09:00:00.000+09:00",
                  "endTime": "2024-03-01T09:30:00.000+09:00",
                  "activity": {
                    "start": { "latLng": "35.6812°, 139.7671°" },
                    "end": { "latLng": "35.6586°, 139.7454°" },
                    "distanceMeters": 3200.0,
                    "topCandidate": { "type": "IN_SUBWAY", "probability": 0.7 }
                  }
                },
                {
                  "startTime": "2024-03-01T09:00:00.000+09:00",
                  "endTime": "2024-03-01T11:00:00.000+09:00",
                  "timelinePath": [
                    { "point": "35.6750°, 139.7600°", "time": "2024-03-01T09:10:00.000+09:00" },
                    { "point": "35.6650°, 139.7500°", "time": "2024-03-01T09:20:00.000+09:00" }
                  ]
                },
                { "startTime": "2024-03-01T12:00:00.000+09:00", "timelineMemory": { "trip": {} } }
              ],
              "rawSignals": [
                { "position": { "LatLng": "35.0°, 139.0°", "accuracyMeters": 10, "timestamp": "2024-03-01T09:15:00.000+09:00" } },
                { "wifiScan": { "deliveryTime": "2024-03-01T09:15:00.000+09:00", "devicesRecords": [] } }
              ],
              "userLocationProfile": { "frequentPlaces": [ { "placeId": "x", "placeLocation": "35.0°, 139.0°" } ] }
            }
            """,
        )
        assertArrayEquals(
            longArrayOf(
                millis("2024-03-01T08:00:00+09:00"),
                millis("2024-03-01T09:00:00+09:00"),
                millis("2024-03-01T09:10:00+09:00"),
                millis("2024-03-01T09:20:00+09:00"),
                millis("2024-03-01T09:30:00+09:00"),
            ),
            timeline.times,
        )
        assertEquals(35.6812, timeline.lat(0), 1e-9)
        assertEquals(139.7671, timeline.lon(0), 1e-9)
        assertEquals(35.6586, timeline.lat(4), 1e-9)
        assertEquals(540.toShort(), timeline.offsets[2])
    }

    @Test
    fun fillsUncoveredTimesWithRawSignals() {
        val timeline = parse(
            """
            { "semanticSegments": [
                { "startTime": "2026-01-01T00:00:00Z", "endTime": "2026-01-03T00:00:00Z",
                  "activity": { "start": "geo:37.5665,126.9780", "end": "geo:37.5700,126.9800" } }
              ],
              "rawSignals": [
                { "position": { "LatLng": "geo:37.5600,126.9700", "timestamp": "2026-01-02T00:00:00Z", "accuracyMeters": 10 } },
                { "position": { "LatLng": "geo:37.5000,127.0000", "timestamp": "2026-02-01T00:00:00Z", "accuracyMeters": 10 } },
                { "position": { "LatLng": "geo:37.5100,127.0100", "timestamp": "2026-02-05T00:10:00Z", "accuracyMeters": 10 } }
              ] }
            """,
        )
        assertArrayEquals(
            longArrayOf(
                millis("2026-01-01T00:00:00Z"), millis("2026-01-03T00:00:00Z"),
                millis("2026-02-01T00:00:00Z"), millis("2026-02-05T00:10:00Z"),
            ),
            timeline.times,
        )
    }

    @Test
    fun fallsBackToRawSignals() {
        val timeline = parse(
            """
            { "rawSignals": [
              { "position": { "LatLng": "35.5°, 139.5°", "accuracyMeters": 10, "timestamp": "2024-03-01T09:15:00.000+09:00" } },
              { "position": { "LatLng": "35.6°, 139.6°", "accuracyMeters": 4000, "timestamp": "2024-03-01T09:16:00.000+09:00" } },
              { "position": { "latLng": "35.7°, 139.7°", "timestamp": "2024-03-01T09:17:00.000+09:00" } }
            ] }
            """,
        )
        assertEquals(2, timeline.size)
        assertEquals(35.7, timeline.lat(1), 1e-9)
    }

    @Test
    fun readsIPhoneExport() {
        val timeline = parse(
            """
            [
              {"endTime": "2024-03-02T10:00:00.000+01:00", "startTime": "2024-03-02T08:00:00.000+01:00",
               "visit": {"hierarchyLevel": "0", "topCandidate": {"probability": "0.9", "semanticType": "Home",
               "placeID": "x", "placeLocation": "geo:52.520008,13.404954"}, "probability": "0.8"}},
              {"endTime": "2024-03-02T10:30:00.000+01:00", "startTime": "2024-03-02T10:00:00.000+01:00",
               "activity": {"probability": "0.9", "end": "geo:52.516275,13.377704",
               "topCandidate": {"type": "walking", "probability": "0.8"}, "distanceMeters": "2100",
               "start": "geo:52.520008,13.404954"}},
              {"endTime": "2024-03-02T12:00:00.000+01:00", "startTime": "2024-03-02T10:00:00.000+01:00",
               "timelinePath": [{"point": "geo:52.518,13.39", "durationMinutesOffsetFromStartTime": "15"}]}
            ]
            """,
        )
        assertArrayEquals(
            longArrayOf(
                millis("2024-03-02T08:00:00+01:00"),
                millis("2024-03-02T10:00:00+01:00"),
                millis("2024-03-02T10:15:00+01:00"),
                millis("2024-03-02T10:30:00+01:00"),
            ),
            timeline.times,
        )
        assertEquals(52.518, timeline.lat(2), 1e-9)
        assertEquals(60.toShort(), timeline.offsets[2])
    }

    @Test
    fun readsTakeoutRecords() {
        val timeline = parse(
            """
            {"locations": [
              {"latitudeE7": 473765210, "longitudeE7": 85412340, "accuracy": 20, "timestamp": "2019-05-01T10:00:00.000Z"},
              {"latitudeE7": 473765999, "longitudeE7": 85412999, "accuracy": 900, "timestamp": "2019-05-01T10:05:00.000Z"},
              {"latitudeE7": 473800000, "longitudeE7": 85500000, "accuracy": 15, "timestampMs": "1556705400000"}
            ]}
            """,
        )
        assertArrayEquals(longArrayOf(millis("2019-05-01T10:00:00Z"), 1_556_705_400_000L), timeline.times)
        assertEquals(47.376521, timeline.lat(0), 1e-9)
        assertEquals(Timeline.NO_OFFSET, timeline.offsets[0])
    }

    @Test
    fun readsTakeoutSemanticHistory() {
        val timeline = parse(
            """
            {"timelineObjects": [
              {"placeVisit": {"location": {"latitudeE7": 407127000, "longitudeE7": -740059000, "name": "Office"},
                "duration": {"startTimestamp": "2020-01-01T09:00:00Z", "endTimestamp": "2020-01-01T17:00:00Z"}}},
              {"activitySegment": {"startLocation": {"latitudeE7": 407127000, "longitudeE7": -740059000},
                "endLocation": {"latitudeE7": 407580000, "longitudeE7": -739855000},
                "duration": {"startTimestampMs": "1577898000000", "endTimestampMs": "1577899800000"},
                "waypointPath": {"waypoints": [{"latE7": 407300000, "lngE7": -739950000}]}}},
              {"activitySegment": {"startLocation": {"latitudeE7": 407580000, "longitudeE7": -739855000},
                "endLocation": {"latitudeE7": 407000000, "longitudeE7": -740000000},
                "duration": {"startTimestamp": "2020-01-01T18:00:00Z", "endTimestamp": "2020-01-01T19:00:00Z"},
                "simplifiedRawPath": {"points": [{"latE7": 407400000, "lngE7": -739900000, "timestamp": "2020-01-01T18:20:00Z"}]}}}
            ]}
            """,
        )
        assertArrayEquals(
            longArrayOf(
                millis("2020-01-01T09:00:00Z"), millis("2020-01-01T17:00:00Z"), millis("2020-01-01T17:15:00Z"),
                millis("2020-01-01T17:30:00Z"), millis("2020-01-01T18:00:00Z"), millis("2020-01-01T18:20:00Z"),
                millis("2020-01-01T19:00:00Z"),
            ),
            timeline.times,
        )
        assertEquals(40.73, timeline.lat(2), 1e-9)
        assertEquals(40.74, timeline.lat(5), 1e-9)
    }

    @Test
    fun rejectsOtherJson() {
        assertThrows(TimelineFormatException::class.java) { parse("""{"type": "FeatureCollection"}""") }
        assertThrows(TimelineFormatException::class.java) { parse("42") }
    }

    @Test
    fun parsesCoordinateStrings() {
        fun lat(text: String) = (TimelineParser.parseCoordinates(text) shr 32) / 1e7
        fun lon(text: String) = TimelineParser.parseCoordinates(text).toInt() / 1e7
        assertEquals(47.6062, lat("47.6062°, -122.3321°"), 1e-9)
        assertEquals(-122.3321, lon("47.6062°, -122.3321°"), 1e-9)
        assertEquals(-33.8688, lat("geo:-33.8688,151.2093"), 1e-9)
        assertEquals(151.2093, lon("geo:-33.8688,151.2093;u=35"), 1e-9)
        assertEquals(47.6062, lat("476062000, -1223321000"), 1e-9)
        assertEquals(TimelineParser.NO_LOCATION, TimelineParser.parseCoordinates("0°, 0°"))
        assertEquals(TimelineParser.NO_LOCATION, TimelineParser.parseCoordinates("north"))
        assertEquals(TimelineParser.NO_LOCATION, TimelineParser.parseCoordinates("91, 10"))
    }

    @Test
    fun sortsAndRemovesDuplicates() {
        val builder = TimelineBuilder()
        builder.add(3_000, 10, 10, 0, precise = true)
        builder.add(1_000, 10, 10, IsoTime.NO_OFFSET, precise = true)
        builder.add(1_000, 10, 10, 60, precise = true)
        builder.add(2_000, 20, 20, 0, precise = true)
        builder.add(2_000, 30, 30, 0, precise = true)
        val timeline = builder.build()
        assertArrayEquals(longArrayOf(1_000, 2_000, 2_000, 3_000), timeline.times)
        assertArrayEquals(intArrayOf(10, 20, 30, 10), timeline.latE7)
        assertEquals(60.toShort(), timeline.offsets[0])
    }

    @Test
    fun sortsLargeShuffledInputStably() {
        val count = 5_000
        val keys = LongArray(count) { (it * 7_919L) % 1_000 }
        val order = sortedOrder(keys, count)
        for (i in 1 until count) {
            val a = order[i - 1]
            val b = order[i]
            assert(keys[a] < keys[b] || (keys[a] == keys[b] && a < b))
        }
    }

    @Test
    fun cacheRoundTrips() {
        val builder = TimelineBuilder()
        for (i in 0 until 200_000) builder.add(i * 1000L, 400_000_000 + i, -700_000_000 - i, if (i % 2 == 0) 60 else IsoTime.NO_OFFSET, true)
        val timeline = builder.build()
        val file = File(folder.root, "timeline.bin")
        TimelineCodec.write(timeline, file)
        val read = TimelineCodec.read(file)
        assertArrayEquals(timeline.times, read.times)
        assertArrayEquals(timeline.latE7, read.latE7)
        assertArrayEquals(timeline.lonE7, read.lonE7)
        assertArrayEquals(timeline.offsets, read.offsets)
        assertNotEquals(Timeline.NO_OFFSET, read.offsets[0])
        assertEquals(Timeline.NO_OFFSET, read.offsets[1])
    }
}
