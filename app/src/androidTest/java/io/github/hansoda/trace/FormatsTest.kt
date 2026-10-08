package io.github.hansoda.trace

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.settings.Units
import io.github.hansoda.trace.ui.Formats
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Text that depends on the phone's own formats. */
@RunWith(AndroidJUnit4::class)
class FormatsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun distance(meters: Double, units: Units, language: String) =
        Formats.distance(meters, units, Locale.forLanguageTag(language)).replace('\u00A0', ' ')

    @Test
    fun writesDistancesInTheirLanguage() {
        assertEquals("5.9 km", distance(5_940.0, Units.KILOMETERS, "en-GB"))
        assertEquals("5,9 км", distance(5_940.0, Units.KILOMETERS, "ru-RU"))
        assertEquals("12 mi", distance(19_312.0, Units.MILES, "en-US"))
    }

    @Test
    fun showsExactTimesWithTheDates() {
        val day = LocalDate.of(2025, 6, 7).toEpochDay()
        val label = Formats.selection(context, DaySelection.range(day, day).withTimes(9 * 60 + 30, 18 * 60)!!)
        assertTrue(label, "2025" in label)
        assertTrue(label, "9:30" in label)
        assertTrue(label, "18:00" in label || "6:00" in label)
    }
}
