package io.github.hansoda.trace.render

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import io.github.hansoda.trace.motion.Plan
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws one video frame: map, route, moving dot and text. The same code renders the live
 * preview and the exported video, so what you see is what you get.
 */
class FrameRenderer {
    private val map = MapPainter()
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trail = Path()

    fun draw(canvas: Canvas, width: Int, height: Int, plan: Plan, frame: Int, look: Look, overlay: Overlay, tiles: TileSource?) {
        val f = frame.coerceIn(0, plan.frameCount - 1)
        val cameraWidth = plan.cameraWidth[f]
        val scale = width / cameraWidth
        val left = plan.cameraX[f] - cameraWidth / 2
        val top = plan.cameraY[f] - height / scale / 2

        fillPaint.color = look.map.background
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), fillPaint)
        if (tiles != null) map.draw(canvas, width, height, plan.cameraX[f], plan.cameraY[f], cameraWidth, look.map.tileSet(look.labels), tiles)

        val shortSide = min(width, height).toFloat()
        val lineWidth = max(1.5f, shortSide * 0.0065f * look.lineWidth)
        buildTrail(plan, f, left, top, scale, width, height, lineWidth * 3)
        drawTrail(canvas, look, lineWidth)
        if (look.showPoints) drawPoints(canvas, plan, f, left, top, scale, width, height, look, lineWidth)
        drawMarkers(canvas, plan, f, left, top, scale, look, lineWidth)
        drawOverlay(canvas, width, height, plan, f, look, overlay)
        drawAttribution(canvas, width, height, look)
    }

    private fun buildTrail(plan: Plan, f: Int, left: Double, top: Double, scale: Double, width: Int, height: Int, margin: Float) {
        trail.rewind()
        val route = plan.route
        val last = plan.headSegment[f]
        val clip = Clip(width, height, margin)
        for (i in 0..last) {
            // Separate days aren't joined up.
            if (route.breakBefore[i]) clip.lift()
            clip.add(trail, ((route.x[i] - left) * scale).toFloat(), ((route.y[i] - top) * scale).toFloat(), false)
        }
        if (!plan.gliding(f)) clip.add(trail, ((plan.headX[f] - left) * scale).toFloat(), ((plan.headY[f] - top) * scale).toFloat(), true)
    }

    private fun drawTrail(canvas: Canvas, look: Look, lineWidth: Float) {
        if (look.map.isDark) {
            linePaint.color = withAlpha(look.routeColor, 0.22f)
            linePaint.strokeWidth = lineWidth * 3.2f
            canvas.drawPath(trail, linePaint)
        } else if (look.map.usesCarto) {
            linePaint.color = withAlpha(0xFFFFFFFF.toInt(), 0.85f)
            linePaint.strokeWidth = lineWidth * 1.9f
            canvas.drawPath(trail, linePaint)
        }
        linePaint.color = look.routeColor
        linePaint.strokeWidth = lineWidth
        canvas.drawPath(trail, linePaint)
    }

    private fun drawPoints(
        canvas: Canvas, plan: Plan, f: Int, left: Double, top: Double, scale: Double,
        width: Int, height: Int, look: Look, lineWidth: Float,
    ) {
        val route = plan.route
        val radius = lineWidth * 0.95f
        val spacing = radius * 2.6f
        var lastX = Float.NaN
        var lastY = Float.NaN
        for (i in 0..plan.headSegment[f]) {
            if (route.breakBefore[i]) lastX = Float.NaN
            val x = ((route.x[i] - left) * scale).toFloat()
            val y = ((route.y[i] - top) * scale).toFloat()
            if (x < -radius || y < -radius || x > width + radius || y > height + radius) continue
            if (!lastX.isNaN() && abs(x - lastX) + abs(y - lastY) < spacing) continue
            fillPaint.color = look.map.background
            canvas.drawCircle(x, y, radius * 1.45f, fillPaint)
            fillPaint.color = look.routeColor
            canvas.drawCircle(x, y, radius, fillPaint)
            lastX = x
            lastY = y
        }
    }

    private fun drawMarkers(canvas: Canvas, plan: Plan, f: Int, left: Double, top: Double, scale: Double, look: Look, lineWidth: Float) {
        val route = plan.route
        val ring = if (look.map.isDark) 0xFF111214.toInt() else 0xFFFFFFFF.toInt()

        // A small hollow ring where the route, and each separate day, starts.
        for (i in 0..plan.headSegment[f]) {
            if (i > 0 && !route.breakBefore[i]) continue
            val startX = ((route.x[i] - left) * scale).toFloat()
            val startY = ((route.y[i] - top) * scale).toFloat()
            fillPaint.color = ring
            canvas.drawCircle(startX, startY, lineWidth * 1.6f, fillPaint)
            fillPaint.color = look.routeColor
            canvas.drawCircle(startX, startY, lineWidth * 1.15f, fillPaint)
            fillPaint.color = ring
            canvas.drawCircle(startX, startY, lineWidth * 0.55f, fillPaint)
        }

        // Head: a dot with a softly breathing halo.
        val x = ((plan.headX[f] - left) * scale).toFloat()
        val y = ((plan.headY[f] - top) * scale).toFloat()
        val seconds = f / plan.fps.toFloat()
        val breathe = 1f + 0.18f * sin(seconds * 2f * Math.PI.toFloat() * 0.8f)
        fillPaint.color = withAlpha(look.routeColor, 0.22f)
        canvas.drawCircle(x, y, lineWidth * 3.4f * breathe, fillPaint)
        fillPaint.color = ring
        canvas.drawCircle(x, y, lineWidth * 2.0f, fillPaint)
        fillPaint.color = look.routeColor
        canvas.drawCircle(x, y, lineWidth * 1.45f, fillPaint)
    }

    private fun drawOverlay(canvas: Canvas, width: Int, height: Int, plan: Plan, f: Int, look: Look, overlay: Overlay) {
        if (overlay.title == null && !overlay.hasSubtitle) return
        val margin = OverlayLayout.margin(width, height)
        val ink = if (look.map.isDark) 0xFFFFFFFF.toInt() else 0xFF151518.toInt()
        val shadow = if (look.map.isDark) 0x99000000.toInt() else 0x99FFFFFF.toInt()
        val maxWidth = width - margin * 2
        var baseline = margin

        overlay.title?.let { title ->
            val size = OverlayLayout.titleSize(width, height)
            textPaint.typeface = TITLE_FACE
            textPaint.textSize = size
            textPaint.color = ink
            textPaint.setShadowLayer(size * 0.18f, 0f, size * 0.03f, shadow)
            baseline += size * 0.95f
            canvas.drawText(ellipsize(title, maxWidth), margin, baseline, textPaint)
            baseline += size * 0.25f
        }

        if (overlay.hasSubtitle) {
            val ending = f >= plan.endingFrame
            val parts = ArrayList<String>(2)
            if (overlay.showDate) {
                if (!ending) {
                    val segment = plan.headSegment[f]
                    parts += overlay.formatDate(plan.headTime[f], plan.route.offsets[segment])
                } else if (overlay.title != overlay.rangeLabel) {
                    parts += overlay.rangeLabel
                }
            }
            if (overlay.showDistance) parts += overlay.formatDistance(if (ending) plan.route.totalMeters else plan.headMeters[f])
            if (parts.isNotEmpty()) {
                val size = OverlayLayout.subtitleSize(width, height)
                textPaint.typeface = BODY_FACE
                textPaint.textSize = size
                textPaint.color = withAlpha(ink, 0.82f)
                textPaint.setShadowLayer(size * 0.2f, 0f, size * 0.04f, shadow)
                baseline += size * (if (overlay.title != null) 1.35f else 0.95f)
                canvas.drawText(ellipsize(parts.joinToString("  ·  "), maxWidth), margin, baseline, textPaint)
            }
        }
        textPaint.clearShadowLayer()
    }

    private fun drawAttribution(canvas: Canvas, width: Int, height: Int, look: Look) {
        val size = max(9f, min(width, height) * 0.017f)
        textPaint.typeface = BODY_FACE
        textPaint.textSize = size
        textPaint.color = if (look.map.isDark) 0x99FFFFFF.toInt() else 0x99000000.toInt()
        val text = look.map.attribution
        val margin = size * 0.9f
        canvas.drawText(text, width - margin - textPaint.measureText(text), height - margin, textPaint)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (textPaint.measureText(text) <= maxWidth) return text
        val fits = textPaint.breakText(text, true, maxWidth - textPaint.measureText("…"), null)
        return text.substring(0, fits.coerceAtLeast(0)).trimEnd() + "…"
    }

    /** Adds points to a path, skipping sub-pixel steps and stretches entirely off screen. */
    private class Clip(private val width: Int, private val height: Int, private val margin: Float) {
        private var hasPrevious = false
        private var previousX = 0f
        private var previousY = 0f
        private var previousCode = 0
        private var penDown = false
        private var drawnX = 0f
        private var drawnY = 0f

        /** Starts a new line at the next point instead of joining it to the last. */
        fun lift() {
            hasPrevious = false
            penDown = false
        }

        fun add(path: Path, x: Float, y: Float, force: Boolean) {
            val code = outcode(x, y)
            if (!hasPrevious) {
                hasPrevious = true
            } else if (previousCode and code != 0) {
                penDown = false
            } else {
                if (!penDown) {
                    path.moveTo(previousX, previousY)
                    drawnX = previousX
                    drawnY = previousY
                    penDown = true
                }
                if (force || abs(x - drawnX) + abs(y - drawnY) >= 0.75f) {
                    path.lineTo(x, y)
                    drawnX = x
                    drawnY = y
                }
            }
            previousX = x
            previousY = y
            previousCode = code
        }

        private fun outcode(x: Float, y: Float): Int {
            var code = 0
            if (x < -margin) code = code or 1 else if (x > width + margin) code = code or 2
            if (y < -margin) code = code or 4 else if (y > height + margin) code = code or 8
            return code
        }
    }

    companion object {
        private val TITLE_FACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private val BODY_FACE: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)

        fun withAlpha(color: Int, alpha: Float): Int =
            ((((color ushr 24) * alpha).toInt().coerceIn(0, 255)) shl 24) or (color and 0x00FFFFFF)
    }
}
