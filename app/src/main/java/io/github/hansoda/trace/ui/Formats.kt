package io.github.hansoda.trace.ui

import android.content.Context
import android.icu.text.MeasureFormat
import android.icu.text.NumberFormat
import android.icu.util.Measure
import android.icu.util.MeasureUnit
import android.text.format.DateFormat
import android.text.format.DateUtils
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.settings.Units
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.SimpleTimeZone
import java.util.TimeZone

/** Locale-aware text for dates and distances. */
object Formats {
    fun dayStart(day: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.ofEpochDay(day).atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayOf(time: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        java.time.Instant.ofEpochMilli(time).atZone(zone).toLocalDate().toEpochDay()

    /** "2 – 5 May 2025", in the device's language. */
    fun range(context: Context, startDay: Long, endDay: Long): String {
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR or DateUtils.FORMAT_ABBREV_MONTH
        return DateUtils.formatDateRange(context, dayStart(startDay), dayStart(endDay) + 1, flags)
    }

    /**
     * "2 – 5 May 2025" for one range; "3 May, 5 May, 9 May 2025" for a few separate ones; and
     * "12 days, 3 May – 9 Jun 2025" for more.
     */
    fun selection(context: Context, days: DaySelection): String {
        if (days.isRange && days.hasTimes) return timedRange(context, days)
        if (days.isRange) return range(context, days.first, days.last)
        if (days.rangeCount > 3) {
            val count = context.resources.getQuantityString(io.github.hansoda.trace.R.plurals.days, days.dayCount, days.dayCount)
            return "$count, " + range(context, days.first, days.last)
        }
        val sameYear = LocalDate.ofEpochDay(days.first).year == LocalDate.ofEpochDay(days.last).year
        val parts = (0 until days.rangeCount).map { k ->
            val last = k == days.rangeCount - 1
            val year = if (!sameYear || last) DateUtils.FORMAT_SHOW_YEAR else DateUtils.FORMAT_NO_YEAR
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or year
            DateUtils.formatDateRange(context, dayStart(days.start(k)), dayStart(days.end(k)) + 1, flags)
        }
        return parts.joinToString(", ")
    }

    /**
     * "7 Jun 2025, 09:30–18:00", or "2 May 2025, 09:30 – 5 May 2025, 18:00": the times as the
     * trip's own clock showed them.
     */
    private fun timedRange(context: Context, days: DaySelection): String {
        fun wallClock(day: Long, minute: Int) =
            LocalDate.ofEpochDay(day).atStartOfDay().plusMinutes(minute.toLong()).toInstant(ZoneOffset.UTC).toEpochMilli()
        val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_SHOW_YEAR
        // The phone's 12- or 24-hour clock comes from the context.
        return DateUtils.formatDateRange(
            context, java.util.Formatter(StringBuilder(), Locale.getDefault()),
            wallClock(days.first, days.startMinute), wallClock(days.last, days.endMinute), flags, "UTC",
        ).toString()
    }

    /** "09:30", or "9:30 AM", as the phone tells the time. */
    fun clock(context: Context, minute: Int): String {
        val format = DateFormat.getTimeFormat(context)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(minute * 60_000L))
    }

    /** "6 May 2025, 14:05", in the time zone the person was in when the export says. */
    fun moment(time: Long, offsetMinutes: Short): String {
        val format = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
        if (offsetMinutes != Timeline.NO_OFFSET) format.timeZone = SimpleTimeZone(offsetMinutes * 60_000, "local")
        return format.format(Date(time))
    }

    /** "5.9 km", or "5,9 км" in Russian. */
    fun distance(meters: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        val value = if (units == Units.MILES) meters / 1609.344 else meters / 1000
        val digits = if (value < 10) 1 else 0
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        val unit = if (units == Units.MILES) MeasureUnit.MILE else MeasureUnit.KILOMETER
        return MeasureFormat.getInstance(locale, MeasureFormat.FormatWidth.SHORT, number).format(Measure(value, unit))
    }

    /** "Sat, 7 Jun, 10:12": when a photo was taken, in the time zone the trip was in when known. */
    class PhotoTime(context: Context) : (Long, Short) -> String {
        private val format: SimpleDateFormat

        init {
            val locale = Locale.getDefault()
            val skeleton = if (DateFormat.is24HourFormat(context)) "EEEdMMMHHmm" else "EEEdMMMhmm"
            format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }

        override fun invoke(time: Long, offsetMinutes: Short): String {
            format.timeZone = if (offsetMinutes == Timeline.NO_OFFSET) TimeZone.getDefault() else SimpleTimeZone(offsetMinutes * 60_000, "local")
            return format.format(Date(time))
        }
    }

    /**
     * Formats the running date on the video. Short ranges show the time of day too; times are
     * shown in the time zone the person was in, when the export says.
     */
    class DateLabel(context: Context, selection: DaySelection) : (Long, Short) -> String {
        private val format: SimpleDateFormat
        private var zoneOffset: Short? = null

        init {
            val locale = Locale.getDefault()
            val days = selection.dayCount
            val sameYear = LocalDate.ofEpochDay(selection.first).year == LocalDate.ofEpochDay(selection.last).year
            val skeleton = when {
                days <= 3 -> if (DateFormat.is24HourFormat(context)) "EEEdMMMHHmm" else "EEEdMMMhmm"
                days <= 400 && sameYear -> "EEEdMMM"
                days <= 800 -> "dMMMyyyy"
                else -> "MMMyyyy"
            }
            format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
        }

        override fun invoke(time: Long, offsetMinutes: Short): String {
            if (offsetMinutes != zoneOffset) {
                format.timeZone = if (offsetMinutes == Timeline.NO_OFFSET) {
                    TimeZone.getDefault()
                } else {
                    SimpleTimeZone(offsetMinutes * 60_000, "local")
                }
                zoneOffset = offsetMinutes
            }
            return format.format(Date(time))
        }
    }
}
