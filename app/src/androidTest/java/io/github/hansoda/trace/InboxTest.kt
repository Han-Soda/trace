package io.github.hansoda.trace

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hansoda.trace.data.Inbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A Timeline export saved straight into Trace waits to be imported until it's completely written. */
@RunWith(AndroidJUnit4::class)
class InboxTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val folder = DocumentsContract.buildDocumentUri("${context.packageName}.inbox", "inbox")

    @Before
    @After
    fun empty() {
        Inbox.remove(Inbox.files(context))
    }

    @Test
    fun waitsUntilTheSaveIsFinished() {
        val uri = DocumentsContract.createDocument(context.contentResolver, folder, "application/json", "Timeline.json")!!
        // Some apps open the file once to empty it, then again to write.
        context.contentResolver.openFileDescriptor(uri, "w")!!.close()
        settle()
        assertTrue("An empty file is waiting to be imported", Inbox.waiting(context).isEmpty())

        val text = """{"semanticSegments":[{"startTime":"2025-06-07T09:00:00.000-05:00","endTime":"2025-06-07T09:30:00.000-05:00"}]}"""
        ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(uri, "w")!!).use { out ->
            out.write(text.substring(0, 40).toByteArray())
            out.flush()
            settle()
            assertTrue("Half a file is waiting to be imported", Inbox.waiting(context).isEmpty())
            out.write(text.substring(40).toByteArray())
        }
        settle()
        val waiting = Inbox.waiting(context)
        assertEquals(1, waiting.size)
        assertEquals(text, waiting.single().readText())
    }

    @Test
    fun aSecondSaveDoesNotReplaceTheFirst() {
        val first = DocumentsContract.createDocument(context.contentResolver, folder, "application/json", "Timeline.json")!!
        val second = DocumentsContract.createDocument(context.contentResolver, folder, "application/json", "Timeline.json")!!
        for ((uri, text) in listOf(first to "[1]", second to "[2]")) {
            ParcelFileDescriptor.AutoCloseOutputStream(context.contentResolver.openFileDescriptor(uri, "w")!!).use { it.write(text.toByteArray()) }
        }
        settle()
        // Newest first: the newer export holds everything the older one does.
        assertEquals(listOf("[2]", "[1]"), Inbox.waiting(context).map { it.readText() })
    }

    /** Lets the close listeners, which run on the main thread, catch up. */
    private fun settle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        instrumentation.waitForIdleSync()
    }
}
