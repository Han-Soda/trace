package io.github.hansoda.trace.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Parses Timeline timestamps. Exports hold millions of them, so the common
 * `2024-03-01T08:15:30.000+09:00` shape is parsed by hand and only odd ones go through
 * java.time. Not thread-safe: results land in [millis] and [offset].
 */
class IsoTime {
    var millis: Long = 0
        private set

    /** Minutes east of UTC written in the timestamp, or [NO_OFFSET] for `Z` or none. */
    var offset: Int = NO_OFFSET
        private set

    fun parse(text: String): Boolean {
        val value = text.trim()
        if (value.isEmpty()) return false
        if (value.all { it.isDigit() }) {
            val number = value.toLongOrNull() ?: return false
            millis = if (number < 100_000_000_000L) number * 1000 else number
            offset = NO_OFFSET
            return true
        }
        return parseFast(value) || parseSlow(value)
    }

    private fun parseFast(s: String): Boolean {
        val n = s.length
        if (n < 16 || s[4] != '-' || s[7] != '-' || (s[10] != 'T' && s[10] != ' ') || s[13] != ':') return false
        val year = digits(s, 0, 4)
        val month = digits(s, 5, 2)
        val day = digits(s, 8, 2)
        val hour = digits(s, 11, 2)
        val minute = digits(s, 14, 2)
        if (year < 0 || month !in 1..12 || day !in 1..31 || hour !in 0..23 || minute !in 0..59) return false
        var i = 16
        var second = 0
        var millisOfSecond = 0
        if (i < n && s[i] == ':') {
            second = digits(s, i + 1, 2)
            if (second !in 0..60) return false
            i += 3
        }
        if (i < n && (s[i] == '.' || s[i] == ',')) {
            i++
            var scale = 100
            val start = i
            while (i < n && s[i].isDigit()) {
                if (scale > 0) millisOfSecond += (s[i] - '0') * scale
                scale /= 10
                i++
            }
            if (i == start) return false
        }
        var offsetMinutes = NO_OFFSET
        var shift = 0
        if (i < n) {
            when (s[i]) {
                'Z', 'z' -> i++
                '+', '-' -> {
                    val sign = if (s[i] == '-') -1 else 1
                    val hours = digits(s, i + 1, 2)
                    if (hours !in 0..18) return false
                    i += 3
                    var minutes = 0
                    if (i < n && s[i] == ':') i++
                    if (i + 2 <= n) {
                        minutes = digits(s, i, 2)
                        if (minutes !in 0..59) return false
                        i += 2
                    }
                    offsetMinutes = sign * (hours * 60 + minutes)
                    shift = offsetMinutes
                }
                else -> return false
            }
        }
        if (i != n) return false
        val days = daysFromCivil(year, month, day)
        millis = ((days * 24 + hour) * 60 + minute - shift) * 60_000L + second * 1000L + millisOfSecond
        offset = offsetMinutes
        return true
    }

    private fun parseSlow(value: String): Boolean {
        runCatching { OffsetDateTime.parse(value) }.getOrNull()?.let {
            millis = it.toInstant().toEpochMilli()
            offset = if (it.offset == ZoneOffset.UTC && value.endsWith("Z", ignoreCase = true)) NO_OFFSET else it.offset.totalSeconds / 60
            return true
        }
        runCatching { Instant.parse(value) }.getOrNull()?.let {
            millis = it.toEpochMilli()
            offset = NO_OFFSET
            return true
        }
        runCatching { LocalDateTime.parse(value) }.getOrNull()?.let {
            millis = it.toInstant(ZoneOffset.UTC).toEpochMilli()
            offset = NO_OFFSET
            return true
        }
        return false
    }

    companion object {
        const val NO_OFFSET = Int.MIN_VALUE

        private fun digits(s: String, from: Int, count: Int): Int {
            if (from + count > s.length) return -1
            var value = 0
            for (index in from until from + count) {
                val c = s[index]
                if (c !in '0'..'9') return -1
                value = value * 10 + (c - '0')
            }
            return value
        }

        /** Days since 1970-01-01 in the proleptic Gregorian calendar (Howard Hinnant's algorithm). */
        fun daysFromCivil(year: Int, month: Int, day: Int): Long {
            val y = (if (month <= 2) year - 1 else year).toLong()
            val era = (if (y >= 0) y else y - 399) / 400
            val yearOfEra = y - era * 400
            val shiftedMonth = (month + 9) % 12
            val dayOfYear = (153 * shiftedMonth + 2) / 5 + day - 1
            val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
            return era * 146_097 + dayOfEra - 719_468
        }
    }
}
