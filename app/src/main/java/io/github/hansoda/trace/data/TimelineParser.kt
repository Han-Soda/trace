package io.github.hansoda.trace.data

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.Reader

/** A file that can't be used, with the reason shown to the person. */
class TimelineFormatException(val reason: Reason) : Exception(reason.name) {
    enum class Reason { NOT_A_TIMELINE, NO_LOCATIONS, EMPTY_ARCHIVE }
}

/**
 * Streams a Google Timeline export into a [PointSink] without loading the whole file.
 *
 * Understands the on-device export (`semanticSegments`, plus `rawSignals` for times the
 * segments don't cover), the iPhone export
 * (a top-level array of segments with `geo:` coordinates), Takeout's `Records.json`
 * (`locations`) and Takeout's monthly Semantic Location History files (`timelineObjects`).
 */
class TimelineParser(private val sink: PointSink) {
    private val time = IsoTime()

    fun parse(input: Reader) {
        val json = JsonReader(input)
        json.strictness = Strictness.LENIENT
        when (json.peek()) {
            JsonToken.BEGIN_ARRAY -> readArray(json) { readSegment(it) }
            JsonToken.BEGIN_OBJECT -> readRoot(json)
            else -> throw TimelineFormatException(TimelineFormatException.Reason.NOT_A_TIMELINE)
        }
    }

    private fun readRoot(json: JsonReader) {
        var recognized = false
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "semanticSegments" -> { recognized = true; readArray(json) { readSegment(it) } }
                "rawSignals" -> { recognized = true; readArray(json) { readRawSignal(it) } }
                "locations" -> { recognized = true; readArray(json) { readRecord(it) } }
                "timelineObjects" -> { recognized = true; readArray(json) { readLegacyObject(it) } }
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (!recognized) throw TimelineFormatException(TimelineFormatException.Reason.NOT_A_TIMELINE)
    }

    // region On-device and iPhone exports

    private fun readSegment(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        var start = NO_TIME
        var startOffset = NO_OFFSET
        var end = NO_TIME
        var endOffset = NO_OFFSET
        var visit = NO_LOCATION
        var activityStart = NO_LOCATION
        var activityEnd = NO_LOCATION
        var path: ArrayList<PathPoint>? = null
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "startTime" -> if (readTime(json)) { start = time.millis; if (time.offset != NO_OFFSET) startOffset = time.offset }
                "endTime" -> if (readTime(json)) { end = time.millis; if (time.offset != NO_OFFSET) endOffset = time.offset }
                "startTimeTimezoneUtcOffsetMinutes" -> readInt(json)?.let { if (startOffset == NO_OFFSET) startOffset = it }
                "endTimeTimezoneUtcOffsetMinutes" -> readInt(json)?.let { if (endOffset == NO_OFFSET) endOffset = it }
                "visit" -> visit = readPlace(json)
                "activity" -> {
                    if (json.peek() == JsonToken.BEGIN_OBJECT) {
                        json.beginObject()
                        while (json.hasNext()) {
                            when (json.nextName()) {
                                "start", "startLocation" -> activityStart = readLocation(json)
                                "end", "endLocation" -> activityEnd = readLocation(json)
                                else -> json.skipValue()
                            }
                        }
                        json.endObject()
                    } else {
                        json.skipValue()
                    }
                }
                "timelinePath" -> path = readPath(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (endOffset == NO_OFFSET) endOffset = startOffset
        if (startOffset == NO_OFFSET) startOffset = endOffset
        if (start != NO_TIME) sink.covered(start, if (end != NO_TIME) end else start)

        if (visit != NO_LOCATION) {
            emit(start, visit, startOffset, precise = true)
            emit(end, visit, endOffset, precise = true)
        }
        if (activityStart != NO_LOCATION) emit(start, activityStart, startOffset, precise = true)
        if (activityEnd != NO_LOCATION) emit(end, activityEnd, endOffset, precise = true)
        path?.forEach { point ->
            val at = when {
                point.time != NO_TIME -> point.time
                start != NO_TIME && !point.minutes.isNaN() -> start + (point.minutes * 60_000).toLong()
                else -> NO_TIME
            }
            emit(at, point.location, if (point.offset != NO_OFFSET) point.offset else startOffset, precise = true)
        }
    }

    private fun readPath(json: JsonReader): ArrayList<PathPoint> {
        val points = ArrayList<PathPoint>()
        readArray(json) { reader ->
            if (reader.peek() != JsonToken.BEGIN_OBJECT) {
                reader.skipValue()
                return@readArray
            }
            var location = NO_LOCATION
            var at = NO_TIME
            var offset = NO_OFFSET
            var minutes = Double.NaN
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "point", "latLng", "LatLng" -> location = readLocation(reader)
                    "time", "timestamp" -> if (readTime(reader)) { at = time.millis; offset = time.offset }
                    "durationMinutesOffsetFromStartTime" -> minutes = readDouble(reader)
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            if (location != NO_LOCATION) points += PathPoint(location, at, offset, minutes)
        }
        return points
    }

    private fun readRawSignal(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == "position" && json.peek() == JsonToken.BEGIN_OBJECT) {
                var location = NO_LOCATION
                var at = NO_TIME
                var offset = NO_OFFSET
                var accuracy = 0.0
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "LatLng", "latLng", "point" -> location = readLocation(json)
                        "timestamp", "time" -> if (readTime(json)) { at = time.millis; offset = time.offset }
                        "accuracyMeters" -> accuracy = readDouble(json)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (!(accuracy > MAX_ACCURACY_METERS)) emit(at, location, offset, precise = false)
            } else {
                json.skipValue()
            }
        }
        json.endObject()
    }

    // endregion

    // region Takeout files

    private fun readRecord(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        var lat = Long.MIN_VALUE
        var lon = Long.MIN_VALUE
        var at = NO_TIME
        var offset = NO_OFFSET
        var accuracy = 0.0
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "latitudeE7" -> lat = readLong(json) ?: Long.MIN_VALUE
                "longitudeE7" -> lon = readLong(json) ?: Long.MIN_VALUE
                "timestamp", "timestampMs" -> if (readTime(json)) { at = time.millis; offset = time.offset }
                "accuracy" -> accuracy = readDouble(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (!(accuracy > MAX_ACCURACY_METERS)) emit(at, packE7(lat, lon), offset, precise = false)
    }

    private fun readLegacyObject(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "placeVisit" -> readPlaceVisit(json)
                "activitySegment" -> readActivitySegment(json)
                else -> json.skipValue()
            }
        }
        json.endObject()
    }

    private fun readPlaceVisit(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        var location = NO_LOCATION
        var centerLat = Long.MIN_VALUE
        var centerLon = Long.MIN_VALUE
        val duration = Duration()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "location" -> location = readLocation(json)
                "centerLatE7" -> centerLat = readLong(json) ?: Long.MIN_VALUE
                "centerLngE7" -> centerLon = readLong(json) ?: Long.MIN_VALUE
                "duration" -> readDuration(json, duration)
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (location == NO_LOCATION) location = packE7(centerLat, centerLon)
        if (duration.start != NO_TIME && duration.end != NO_TIME) sink.covered(duration.start, duration.end)
        emit(duration.start, location, duration.startOffset, precise = true)
        emit(duration.end, location, duration.endOffset, precise = true)
    }

    private fun readActivitySegment(json: JsonReader) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        var start = NO_LOCATION
        var end = NO_LOCATION
        val duration = Duration()
        val waypoints = ArrayList<Long>()
        val rawPath = ArrayList<PathPoint>()
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "startLocation" -> start = readLocation(json)
                "endLocation" -> end = readLocation(json)
                "duration" -> readDuration(json, duration)
                "waypointPath" -> readNestedArray(json, "waypoints") { waypoints += readLocation(it) }
                "simplifiedRawPath" -> readNestedArray(json, "points") { reader ->
                    var lat = Long.MIN_VALUE
                    var lon = Long.MIN_VALUE
                    var at = NO_TIME
                    var offset = NO_OFFSET
                    if (reader.peek() == JsonToken.BEGIN_OBJECT) {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            when (reader.nextName()) {
                                "latE7", "latitudeE7" -> lat = readLong(reader) ?: Long.MIN_VALUE
                                "lngE7", "longitudeE7" -> lon = readLong(reader) ?: Long.MIN_VALUE
                                "timestamp", "timestampMs" -> if (readTime(reader)) { at = time.millis; offset = time.offset }
                                else -> reader.skipValue()
                            }
                        }
                        reader.endObject()
                    } else {
                        reader.skipValue()
                    }
                    val location = packE7(lat, lon)
                    if (isValid(location)) rawPath += PathPoint(location, at, offset, Double.NaN)
                }
                else -> json.skipValue()
            }
        }
        json.endObject()
        if (duration.start != NO_TIME && duration.end != NO_TIME) sink.covered(duration.start, duration.end)
        emit(duration.start, start, duration.startOffset, precise = true)
        emit(duration.end, end, duration.endOffset, precise = true)
        if (rawPath.isNotEmpty()) {
            rawPath.forEach { emit(it.time, it.location, if (it.offset != NO_OFFSET) it.offset else duration.startOffset, precise = true) }
        } else if (duration.start != NO_TIME && duration.end != NO_TIME) {
            val valid = waypoints.filter { isValid(it) }
            valid.forEachIndexed { index, location ->
                val fraction = (index + 1).toDouble() / (valid.size + 1)
                val at = duration.start + ((duration.end - duration.start) * fraction).toLong()
                emit(at, location, duration.startOffset, precise = true)
            }
        }
    }

    private class Duration {
        var start = NO_TIME
        var startOffset = NO_OFFSET
        var end = NO_TIME
        var endOffset = NO_OFFSET
    }

    private fun readDuration(json: JsonReader, into: Duration) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "startTimestamp", "startTimestampMs" -> if (readTime(json)) { into.start = time.millis; into.startOffset = time.offset }
                "endTimestamp", "endTimestampMs" -> if (readTime(json)) { into.end = time.millis; into.endOffset = time.offset }
                else -> json.skipValue()
            }
        }
        json.endObject()
    }

    // endregion

    // region Values

    /** Reads a place in any of the shapes the exports use: a string, or an object holding one. */
    private fun readPlace(json: JsonReader): Long {
        if (json.peek() != JsonToken.BEGIN_OBJECT) return readLocation(json)
        var found = NO_LOCATION
        json.beginObject()
        while (json.hasNext()) {
            when (json.nextName()) {
                "topCandidate" -> readPlace(json).let { if (found == NO_LOCATION) found = it }
                "placeLocation", "location", "latLng", "LatLng", "point" ->
                    readLocation(json).let { if (found == NO_LOCATION) found = it }
                else -> json.skipValue()
            }
        }
        json.endObject()
        return found
    }

    private fun readLocation(json: JsonReader): Long {
        return when (json.peek()) {
            JsonToken.STRING -> parseCoordinates(json.nextString())
            JsonToken.BEGIN_OBJECT -> {
                var location = NO_LOCATION
                var lat = Double.NaN
                var lon = Double.NaN
                json.beginObject()
                while (json.hasNext()) {
                    when (json.nextName()) {
                        "latLng", "LatLng", "point", "placeLocation" ->
                            readLocation(json).let { if (location == NO_LOCATION) location = it }
                        "latitudeE7", "latE7" -> lat = (readLong(json) ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) Double.NaN else it / 1e7 }
                        "longitudeE7", "lngE7", "lonE7" -> lon = (readLong(json) ?: Long.MIN_VALUE).let { if (it == Long.MIN_VALUE) Double.NaN else it / 1e7 }
                        "latitude", "lat" -> lat = readDouble(json)
                        "longitude", "lng", "lon" -> lon = readDouble(json)
                        else -> json.skipValue()
                    }
                }
                json.endObject()
                if (location == NO_LOCATION) pack(lat, lon) else location
            }
            else -> {
                json.skipValue()
                NO_LOCATION
            }
        }
    }

    private fun readTime(json: JsonReader): Boolean {
        return when (json.peek()) {
            JsonToken.STRING -> time.parse(json.nextString())
            JsonToken.NUMBER -> time.parse(json.nextString())
            else -> {
                json.skipValue()
                false
            }
        }
    }

    private fun readDouble(json: JsonReader): Double = when (json.peek()) {
        JsonToken.NUMBER, JsonToken.STRING -> json.nextString().trim().toDoubleOrNull() ?: Double.NaN
        else -> {
            json.skipValue()
            Double.NaN
        }
    }

    private fun readLong(json: JsonReader): Long? = readDouble(json).takeIf { !it.isNaN() }?.toLong()

    private fun readInt(json: JsonReader): Int? = readLong(json)?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()

    private inline fun readArray(json: JsonReader, item: (JsonReader) -> Unit) {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue()
            return
        }
        json.beginArray()
        while (json.hasNext()) item(json)
        json.endArray()
    }

    /** Reads `{ "<key>": [ ... ] }`, ignoring the object's other fields. */
    private inline fun readNestedArray(json: JsonReader, key: String, item: (JsonReader) -> Unit) {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return
        }
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() == key) readArray(json, item) else json.skipValue()
        }
        json.endObject()
    }

    // endregion

    private fun emit(at: Long, location: Long, offset: Int, precise: Boolean) {
        if (at == NO_TIME || !isValid(location)) return
        sink.add(at, (location shr 32).toInt(), location.toInt(), offset, precise)
    }

    private class PathPoint(val location: Long, val time: Long, val offset: Int, val minutes: Double)

    companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val NO_LOCATION = Long.MIN_VALUE
        const val NO_OFFSET = IsoTime.NO_OFFSET
        private const val MAX_ACCURACY_METERS = 250.0

        /** Parses `"47.6°, -122.3°"`, `"geo:47.6,-122.3"` or `"47.6, -122.3"`. */
        fun parseCoordinates(text: String): Long {
            var value = text.trim()
            if (value.startsWith("geo:", ignoreCase = true)) value = value.substring(4)
            value = value.substringBefore('?').substringBefore(';')
            val comma = value.indexOf(',')
            if (comma < 0) return NO_LOCATION
            val lat = cleanNumber(value.substring(0, comma)) ?: return NO_LOCATION
            val lon = cleanNumber(value.substring(comma + 1)) ?: return NO_LOCATION
            return pack(lat, lon)
        }

        private fun cleanNumber(text: String): Double? =
            text.replace("°", "").trim().toDoubleOrNull()

        /** Packs degrees, accepting E7 integers that slipped into degree fields. */
        fun pack(lat: Double, lon: Double): Long {
            if (lat.isNaN() || lon.isNaN()) return NO_LOCATION
            var latitude = lat
            var longitude = lon
            if (kotlin.math.abs(latitude) > 1_000) latitude /= 1e7
            if (kotlin.math.abs(longitude) > 1_000) longitude /= 1e7
            if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return NO_LOCATION
            if (latitude == 0.0 && longitude == 0.0) return NO_LOCATION
            val latE7 = Math.round(latitude * 1e7)
            val lonE7 = Math.round(longitude * 1e7)
            return (latE7 shl 32) or (lonE7 and 0xffffffffL)
        }

        private fun packE7(lat: Long, lon: Long): Long =
            if (lat == Long.MIN_VALUE || lon == Long.MIN_VALUE) NO_LOCATION else pack(lat / 1e7, lon / 1e7)

        private fun isValid(location: Long): Boolean = location != NO_LOCATION

    }
}
