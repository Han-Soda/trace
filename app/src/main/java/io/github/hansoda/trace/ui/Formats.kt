package io.github.hansoda.trace.ui

import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.settings.Units
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneId
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

    fun distance(meters: Double, units: Units, locale: Locale = Locale.getDefault()): String {
        val value = if (units == Units.MILES) meters / 1609.344 else meters / 1000
        val digits = if (value < 10) 1 else 0
        val number = NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = digits
            maximumFractionDigits = digits
        }
        return number.format(value) + if (units == Units.MILES) " mi" else " km"
    }

    /**
     * Formats the running date on the video. Short ranges show the time of day too; times are
     * shown in the time zone the person was in, when the export says.
     */
    class DateLabel(context: Context, startDay: Long, endDay: Long) : (Long, Short) -> String {
        private val format: SimpleDateFormat
        private var zoneOffset: Short? = null

        init {
            val locale = Locale.getDefault()
            val days = endDay - startDay + 1
            val sameYear = LocalDate.ofEpochDay(startDay).year == LocalDate.ofEpochDay(endDay).year
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
