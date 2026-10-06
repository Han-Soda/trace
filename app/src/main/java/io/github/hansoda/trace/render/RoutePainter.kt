package io.github.hansoda.trace.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import io.github.hansoda.trace.route.RangeData
import kotlin.math.abs
import kotlin.math.hypot

/** Where the route editor looks: centre and width in world units. */
data class MapView(val x: Double, val y: Double, val width: Double)

/**
 * Draws the route editor's view: every cleaned-up point of the chosen days, the points that
 * look like GPS errors, and what's selected for removal.
 */
class RoutePainter(private val density: Float) {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val path = Path()

    fun draw(
        canvas: Canvas, width: Int, height: Int, view: MapView, data: RangeData, look: Look,
        suspects: IntArray, selection: IntRange?,
    ) {
        val scale = width / view.width
        val left = view.x - view.width / 2
        val top = view.y - view.width * height / width / 2
        fun sx(i: Int) = ((data.x[i] - left) * scale).toFloat()
        fun sy(i: Int) = ((data.y[i] - top) * scale).toFloat()
        val margin = 40 * density
        fun onScreen(x: Float, y: Float) = x > -margin && y > -margin && x < width + margin && y < height + margin

        // The line, piece by piece.
        trace(data, 0 until data.size, ::sx, ::sy)
        line.color = FrameRenderer.withAlpha(look.routeColor, 0.9f)
        line.strokeWidth = 2.5f * density
        canvas.drawPath(path, line)

        // Points, as long as they don't crowd each other.
        val radius = 3f * density
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (i in 0 until data.size) {
            val x = sx(i)
            val y = sy(i)
            if (!onScreen(x, y)) continue
            if (!lastX.isNaN() && abs(x - lastX) + abs(y - lastY) < radius * 3) continue
            fill.color = look.map.background
            canvas.drawCircle(x, y, radius + 1.2f * density, fill)
            fill.color = look.routeColor
            canvas.drawCircle(x, y, radius, fill)
            lastX = x
            lastY = y
        }

        // Doubtful points get a ring at any zoom, so they're easy to find.
        ring.color = WARNING
        ring.strokeWidth = 2f * density
        for (i in suspects) {
            if (i >= data.size) continue
            val x = sx(i)
            val y = sy(i)
            if (onScreen(x, y)) canvas.drawCircle(x, y, 9f * density, ring)
        }

        selection?.let { range ->
            val first = range.first.coerceIn(0, data.size - 1)
            val last = range.last.coerceIn(0, data.size - 1)
            if (last > first) {
                trace(data, first..last, ::sx, ::sy)
                line.color = SELECTED
                line.strokeWidth = 5f * density
                canvas.drawPath(path, line)
            }
            for (i in setOf(first, last)) {
                fill.color = 0xFFFFFFFF.toInt()
                canvas.drawCircle(sx(i), sy(i), 9f * density, fill)
                fill.color = SELECTED
                canvas.drawCircle(sx(i), sy(i), 6f * density, fill)
            }
        }
    }

    /** Index of the point nearest to ([px], [py]) within [reach] pixels, or -1. */
    fun pointAt(px: Float, py: Float, reach: Float, width: Int, height: Int, view: MapView, data: RangeData): Int {
        val scale = width / view.width
        val left = view.x - view.width / 2
        val top = view.y - view.width * height / width / 2
        var best = -1
        var bestDistance = reach.toDouble()
        for (i in 0 until data.size) {
            val distance = hypot((data.x[i] - left) * scale - px, (data.y[i] - top) * scale - py)
            if (distance < bestDistance) {
                bestDistance = distance
                best = i
            }
        }
        return best
    }

    private inline fun trace(data: RangeData, indices: IntRange, sx: (Int) -> Float, sy: (Int) -> Float) {
        path.rewind()
        var lastX = 0f
        var lastY = 0f
        for (i in indices) {
            val x = sx(i)
            val y = sy(i)
            if (i == indices.first || data.breakBefore[i]) {
                path.moveTo(x, y)
            } else if (abs(x - lastX) + abs(y - lastY) < 0.75f && i != indices.last) {
                continue
            } else {
                path.lineTo(x, y)
            }
            lastX = x
            lastY = y
        }
    }

    companion object {
        const val WARNING = 0xFFE5484D.toInt()
        const val SELECTED = 0xFFE5484D.toInt()
    }
}
