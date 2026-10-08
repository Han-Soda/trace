package io.github.hansoda.trace

import android.content.Context
import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
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
            val target = By.text(button)
            // On a small screen the button is further down the start screen.
            val until = SystemClock.uptimeMillis() + 30_000
            while (!device.hasObject(target) && SystemClock.uptimeMillis() < until) {
                device.findObject(By.scrollable(true))?.scroll(Direction.DOWN, 1f)
                SystemClock.sleep(500)
            }
            assertTrue("No $button button among: ${onScreen()}", device.hasObject(target))
            (device.findObject(target) ?: error("No $button button")).click()
            // Google's Timeline page where Google Play services has one; Location settings here.
            val settings = Pattern.compile("com\\.android\\.settings|com\\.google\\.android\\.gms")
            assertTrue("Settings didn't open: ${onScreen()}", device.wait(Until.hasObject(By.pkg(settings).depth(0)), 15_000))
            device.pressBack()
        }
    }

    /** The package in front and the words on screen, to say what was there instead. */
    private fun onScreen(): String =
        device.currentPackageName + " " + device.findObjects(By.text(Pattern.compile(".+"))).take(20).map { it.text }
}
