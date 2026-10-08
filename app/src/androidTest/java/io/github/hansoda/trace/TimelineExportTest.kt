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
            // Google's Timeline page where Google Play services has one; Location settings here.
            // A tap while the screen still scrolls can miss, and Settings can be slow to start.
            val settings = By.pkg(Pattern.compile("com\\.android\\.settings|com\\.google\\.android\\.gms")).depth(0)
            var opened = false
            for (attempt in 1..2) {
                device.waitForIdle()
                val shown = device.findObject(target) ?: break
                shown.click()
                opened = device.wait(Until.hasObject(settings), 30_000)
                if (opened) break
            }
            assertTrue("Settings didn't open: ${onScreen()}", opened || device.hasObject(settings))
            device.pressBack()
        }
    }

    /** The package in front and the words on screen, to say what was there instead. */
    private fun onScreen(): String = runCatching {
        // The screen can change while it's read.
        device.currentPackageName + " " + device.findObjects(By.text(Pattern.compile(".+"))).take(20).map { runCatching { it.text }.getOrNull() }
    }.getOrElse { "unreadable: $it" }
}
