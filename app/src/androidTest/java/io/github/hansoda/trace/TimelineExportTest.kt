package io.github.hansoda.trace

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import io.github.hansoda.trace.data.TimelineRepository
import io.github.hansoda.trace.settings.AppLanguage
import java.util.regex.Pattern
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The start screen's Export from Timeline button takes the phone's settings to the front. */
@RunWith(AndroidJUnit4::class)
class TimelineExportTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun opensTheTimelineSettings() {
        AppLanguage.choose(context, AppLanguage.ENGLISH)
        TimelineRepository(context).clear()
        val button = AppLanguage.apply(context).getString(R.string.open_timeline_settings)
        ActivityScenario.launch(MainActivity::class.java).use {
            assertTrue("No $button button", device.wait(Until.hasObject(By.text(button)), 30_000))
            (device.findObject(By.text(button)) ?: error("No $button button")).click()
            // Google's Timeline page where Google Play services has one; Location settings here.
            val settings = Pattern.compile("com\\.android\\.settings|com\\.google\\.android\\.gms")
            assertTrue("Settings didn't open", device.wait(Until.hasObject(By.pkg(settings).depth(0)), 15_000))
            device.pressBack()
        }
    }
}
