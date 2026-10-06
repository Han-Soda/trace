package io.github.hansoda.trace.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Draws the map tiles behind any view: a video frame, or the route editor. */
class MapPainter {
    private val tilePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val source = Rect()
    private val target = RectF()

    /** Fills a [width] × [height] view centred on ([centerX], [centerY]), [cameraWidth] world units across. */
    fun draw(canvas: Canvas, width: Int, height: Int, centerX: Double, centerY: Double, cameraWidth: Double, set: String, tiles: TileSource) {
        val aspect = width.toDouble() / height
        val scale = width / cameraWidth
        val left = centerX - cameraWidth / 2
        val top = centerY - cameraWidth / aspect / 2
        for ((z, opacity) in TileMath.levels(TileMath.zoom(cameraWidth, width))) {
            tilePaint.alpha = (opacity * 255).toInt()
            val count = 1 shl z
            TileMath.forEachTile(z, centerX, centerY, cameraWidth, aspect) { tileX, tileY, wrappedX ->
                // Snap edges to whole pixels so neighbouring tiles meet without hairline gaps.
                val x0 = Math.round((tileX.toDouble() / count - left) * scale).toFloat()
                val x1 = Math.round(((tileX + 1).toDouble() / count - left) * scale).toFloat()
                val y0 = Math.round((tileY.toDouble() / count - top) * scale).toFloat()
                val y1 = Math.round(((tileY + 1).toDouble() / count - top) * scale).toFloat()
                target.set(x0, y0, x1, y1)
                drawTileOrAncestor(canvas, TileKey(set, z, wrappedX, tileY), tiles)
            }
        }
        tilePaint.alpha = 255
    }

    /** Draws the tile, or a blown-up piece of a lower-zoom tile while it loads. */
    private fun drawTileOrAncestor(canvas: Canvas, key: TileKey, tiles: TileSource) {
        tiles.tile(key)?.let {
            canvas.drawBitmap(it, null, target, tilePaint)
            return
        }
        for (up in 1..4) {
            val z = key.z - up
            if (z < 0) return
            val parent = tiles.tile(TileKey(key.set, z, key.x shr up, key.y shr up)) ?: continue
            val pieces = 1 shl up
            val size = parent.width / pieces
            val px = (key.x and (pieces - 1)) * size
            val py = (key.y and (pieces - 1)) * size
            source.set(px, py, px + size, py + size)
            canvas.drawBitmap(parent, source, target, tilePaint)
            return
        }
    }
}
