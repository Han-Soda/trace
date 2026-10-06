package io.github.hansoda.trace.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.abs

/**
 * Draws coastlines and lakes from the bundled [LandShapes], so the free styles still show the
 * world's outline when their vector tiles can't be downloaded.
 */
class PlainTiles(private val shapes: () -> LandShapes?) : TileSource {
    private val cache = object : LinkedHashMap<TileKey, Bitmap>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, Bitmap>?): Boolean = size > MAX_TILES
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()

    @Synchronized
    override fun tile(key: TileKey): Bitmap? {
        cache[key]?.let { return it }
        val style = MapStyle.vectorSet(key.set)?.first ?: return null
        val land = shapes() ?: return null
        return render(key, style, land).also { cache[key] = it }
    }

    private fun render(key: TileKey, style: MapStyle, land: LandShapes): Bitmap {
        val size = TileMath.TILE_PIXELS
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(style.sea)
        val unitsPerTile = LandShapes.SCALE.toDouble() / (1 shl key.z)
        val scale = size / unitsPerTile
        val left = key.x * unitsPerTile
        val top = key.y * unitsPerTile
        val margin = unitsPerTile / size * 2
        for (lakes in booleanArrayOf(false, true)) {
            paint.color = if (lakes) style.sea else style.landColor
            for (r in 0 until land.ringCount) {
                if (land.lake[r] != lakes) continue
                if (land.maxX[r] < left - margin || land.minX[r] > left + unitsPerTile + margin ||
                    land.maxY[r] < top - margin || land.minY[r] > top + unitsPerTile + margin
                ) continue
                path.rewind()
                var lastX = 0f
                var lastY = 0f
                for (p in land.start[r] until land.start[r + 1]) {
                    val x = ((land.x[p] - left) * scale).toFloat()
                    val y = ((land.y[p] - top) * scale).toFloat()
                    if (p == land.start[r]) {
                        path.moveTo(x, y)
                    } else if (abs(x - lastX) + abs(y - lastY) < 0.7f) {
                        continue
                    } else {
                        path.lineTo(x, y)
                    }
                    lastX = x
                    lastY = y
                }
                path.close()
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }

    private companion object {
        const val MAX_TILES = 40
    }
}
