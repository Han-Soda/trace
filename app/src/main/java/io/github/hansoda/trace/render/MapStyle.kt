package io.github.hansoda.trace.render

import java.net.URLEncoder

/**
 * The map under the route. Light, Dark and Voyager are CARTO tiles; Paper and Ink are drawn on
 * the device from bundled coastlines, need no network, and fade to a plain background when
 * zoomed into a city.
 */
enum class MapStyle(
    val id: String,
    /** Shown where tiles haven't loaded yet, and under city views of the plain styles. */
    val background: Int,
    val isDark: Boolean,
    private val tiles: String?,
    private val tilesWithoutLabels: String?,
    /** Sea colour of the plain styles; their land uses [background]. */
    val sea: Int = background,
) {
    LIGHT("light", 0xFFF0F0EE.toInt(), false, "light_all", "light_nolabels"),
    DARK("dark", 0xFF202124.toInt(), true, "dark_all", "dark_nolabels"),
    VOYAGER("voyager", 0xFFF3F0EA.toInt(), false, "rastertiles/voyager", "rastertiles/voyager_nolabels"),
    PAPER("paper", 0xFFF4F1EA.toInt(), false, null, null, sea = 0xFFDFE3E3.toInt()),
    INK("ink", 0xFF17181B.toInt(), true, null, null, sea = 0xFF0A0B0D.toInt()),
    ;

    val usesCarto: Boolean get() = tiles != null
    val isPlain: Boolean get() = tiles == null
    val landColor: Int get() = background

    /** Tile set for this style: a CARTO path, or a plain set drawn on the device. */
    fun tileSet(labels: Boolean): String = when {
        isPlain -> PLAIN_PREFIX + id
        labels -> tiles!!
        else -> tilesWithoutLabels!!
    }

    companion object {
        private const val PLAIN_PREFIX = "plain-"

        fun fromId(id: String?): MapStyle = entries.firstOrNull { it.id == id } ?: PAPER

        fun plainFor(set: String): MapStyle? =
            if (set.startsWith(PLAIN_PREFIX)) entries.firstOrNull { it.isPlain && PLAIN_PREFIX + it.id == set } else null

        fun isPlainSet(set: String): Boolean = set.startsWith(PLAIN_PREFIX)
    }
}

/** One 512-pixel CARTO tile. [x] is already wrapped into the map's width. */
data class TileKey(val set: String, val z: Int, val x: Int, val y: Int) {
    fun url(apiKey: String): String {
        val host = "abcd"[(x + y) and 3]
        val base = "https://$host.basemaps.cartocdn.com/$set/$z/$x/$y@2x.png"
        val key = apiKey.trim()
        return if (key.isEmpty()) base else "$base?key=${URLEncoder.encode(key, "UTF-8")}"
    }
}

/** Which tiles a camera needs, shared by drawing and by downloading ahead of an export. */
object TileMath {
    const val TILE_PIXELS = 512
    const val MAX_ZOOM = 19

    /** Plain styles show coastlines out to this zoom and fade them out by [PLAIN_FADE_END]. */
    const val PLAIN_FADE_START = 7.0
    const val PLAIN_FADE_END = 9.0

    /** Fractional tile zoom at which 512-pixel tiles appear at their natural size. */
    fun zoom(cameraWidth: Double, pixelWidth: Int): Double =
        kotlin.math.log2(pixelWidth / (cameraWidth * TILE_PIXELS)).coerceIn(0.0, MAX_ZOOM.toDouble())

    /**
     * The tile levels to draw and how opaque each is. Near a level change the next level
     * fades in over the previous one, so labels don't pop.
     */
    fun levels(zoom: Double): List<Pair<Int, Float>> {
        val base = kotlin.math.floor(zoom).toInt()
        val fraction = zoom - base
        if (base >= MAX_ZOOM) return listOf(MAX_ZOOM to 1f)
        return when {
            fraction < FADE_START -> listOf(base to 1f)
            fraction > FADE_END -> listOf(base + 1 to 1f)
            else -> {
                val t = ((fraction - FADE_START) / (FADE_END - FADE_START)).toFloat()
                listOf(base to 1f, base + 1 to t * t * (3 - 2 * t))
            }
        }
    }

    private const val FADE_START = 0.4
    private const val FADE_END = 0.6

    /** Calls [visit] for every tile at level [z] that overlaps the camera view. */
    inline fun forEachTile(
        z: Int,
        centerX: Double,
        centerY: Double,
        cameraWidth: Double,
        aspect: Double,
        visit: (tileX: Int, tileY: Int, wrappedX: Int) -> Unit,
    ) {
        val count = 1 shl z
        val halfWidth = cameraWidth / 2
        val halfHeight = cameraWidth / aspect / 2
        val firstX = kotlin.math.floor((centerX - halfWidth) * count).toInt()
        val lastX = kotlin.math.floor((centerX + halfWidth) * count).toInt()
        val firstY = kotlin.math.floor((centerY - halfHeight) * count).toInt().coerceAtLeast(0)
        val lastY = kotlin.math.floor((centerY + halfHeight) * count).toInt().coerceAtMost(count - 1)
        for (tileY in firstY..lastY) {
            for (tileX in firstX..lastX) visit(tileX, tileY, Math.floorMod(tileX, count))
        }
    }
}
