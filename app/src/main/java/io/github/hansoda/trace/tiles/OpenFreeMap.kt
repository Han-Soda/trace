package io.github.hansoda.trace.tiles

import android.content.Context
import com.google.gson.JsonParser
import io.github.hansoda.trace.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Where OpenFreeMap's free vector tiles are. The address of the planet tiles changes with each
 * weekly update, so it's read from their TileJSON and remembered for a few days.
 */
class OpenFreeMap(context: Context) {
    private val prefs = context.getSharedPreferences("openfreemap", Context.MODE_PRIVATE)

    @Volatile
    private var template: String? = prefs.getString(TEMPLATE, null)

    @Volatile
    private var checkedAt: Long = prefs.getLong(CHECKED_AT, 0)

    /** Deepest zoom the tiles go to; deeper views draw from these. */
    @Volatile
    var maxZoom: Int = prefs.getInt(MAX_ZOOM, 14)
        private set

    /** Address of one tile, or null if the tile list can't be reached. */
    @Synchronized
    fun url(z: Int, x: Int, y: Int): String? {
        if (template == null || System.currentTimeMillis() - checkedAt > MAX_AGE_MS) refresh()
        return template?.replace("{z}", z.toString())?.replace("{x}", x.toString())?.replace("{y}", y.toString())
    }

    /** Looks the address up again next time, after a tile came back missing. */
    @Synchronized
    fun stale() {
        checkedAt = 0
    }

    private fun refresh() {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(TILE_JSON).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", USER_AGENT)
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${connection.responseCode}")
            val json = connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            val root = JsonParser.parseString(json).asJsonObject
            val tiles = root.getAsJsonArray("tiles")?.firstOrNull()?.asString ?: throw IOException("No tiles listed")
            template = tiles
            maxZoom = root.get("maxzoom")?.asInt?.coerceIn(0, 16) ?: 14
            checkedAt = System.currentTimeMillis()
            prefs.edit().putString(TEMPLATE, tiles).putInt(MAX_ZOOM, maxZoom).putLong(CHECKED_AT, checkedAt).apply()
        } catch (_: Exception) {
            // Keep any address we had; try again in a minute.
            checkedAt = System.currentTimeMillis() - MAX_AGE_MS + RETRY_MS
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        const val TILE_JSON = "https://tiles.openfreemap.org/planet"
        val USER_AGENT = "Trace/${BuildConfig.VERSION_NAME} (Android)"

        private const val TEMPLATE = "template"
        private const val MAX_ZOOM = "max_zoom"
        private const val CHECKED_AT = "checked_at"
        private const val MAX_AGE_MS = 3 * 24 * 3_600_000L
        private const val RETRY_MS = 60_000L
    }
}
