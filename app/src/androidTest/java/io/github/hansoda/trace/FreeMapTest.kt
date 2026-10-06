package io.github.hansoda.trace

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.render.TileKey
import io.github.hansoda.trace.render.VectorTile
import io.github.hansoda.trace.tiles.OpenFreeMap
import io.github.hansoda.trace.tiles.TileStore
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The free styles are drawn from OpenFreeMap's vector tiles; these fetch real ones. */
@RunWith(AndroidJUnit4::class)
class FreeMapTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    // Around the Reichstag in Berlin: streets, buildings and the Spree.
    private val z = 14
    private val x = 8800
    private val y = 5373

    @Test
    fun readsOpenFreeMapTiles() {
        val url = OpenFreeMap(context).url(z, x, y) ?: throw AssertionError("OpenFreeMap's tile address couldn't be read")
        val connection = URL(url).openConnection() as HttpURLConnection
        val bytes = try {
            connection.setRequestProperty("User-Agent", OpenFreeMap.USER_AGENT)
            assertEquals(HttpURLConnection.HTTP_OK, connection.responseCode)
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
        val tile = VectorTile.decode(bytes)
        for (layer in listOf("transportation", "building", "water")) {
            assertTrue("no $layer in ${tile.layerNames}", (tile.layer(layer)?.features?.size ?: 0) > 0)
        }
    }

    @Test
    fun drawsStreetsLikeAnExport() = runBlocking {
        val tiles = TileStore(context)
        tiles.clear()
        val key = TileKey(MapStyle.STREETS.tileSet(true), z, x, y)
        assertEquals("tiles that couldn't be downloaded", 0, tiles.prefetch(listOf(key)) { _, _ -> })
        val bitmap = tiles.tileNow(key) ?: throw AssertionError("No tile drawn")
        // Drawn from the bundled coastlines instead, this inland tile would be one flat colour.
        assertTrue("too few colours for a street map", colours(bitmap) > 50)
    }

    private fun colours(bitmap: Bitmap): Int {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.toHashSet().size
    }
}
