package io.github.hansoda.trace.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.motion.PlannedMoment
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a video's photos and clips: each grows out of the map where it was taken, stays while
 * the dot waits there, then shrinks back into a pin that stays on the map.
 */
class MomentPainter {
    private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val source = Rect()
    private val target = RectF()
    private val border = RectF()
    private val at = FloatArray(2)

    /**
     * @param top height of the title at the top, which cards stay clear of.
     * @param place puts the screen position of a map point into its third argument.
     */
    fun draw(
        canvas: Canvas, width: Int, height: Int, plan: Plan, f: Int, photos: PhotoSource, top: Float,
        place: (x: Double, y: Double, out: FloatArray) -> Unit,
    ) {
        val shortSide = min(width, height).toFloat()
        val pin = shortSide * PIN
        // Pins of the moments already shown, a little apart where several share a place.
        var previousPoint = -1
        var sharing = 0
        for (moment in plan.moments) {
            if (f <= moment.endFrame) continue
            sharing = if (moment.point == previousPoint) sharing + 1 else 0
            previousPoint = moment.point
            place(plan.route.x[moment.point], plan.route.y[moment.point], at)
            val x = at[0] + sharing * pin * 0.4f
            if (x < -pin || x > width + pin || at[1] < -pin || at[1] > height + pin * 2) continue
            drawPicture(canvas, photos.thumbnail(moment.moment.id), pinFrame(x, at[1], pin), 1f, 0f, shortSide)
        }
        // The one on screen now, if any.
        val showing = plan.moments.firstOrNull { f in it.startFrame..it.endFrame } ?: return
        drawCard(canvas, width, height, plan, f, showing, photos, top, place, pin, shortSide)
    }

    private fun drawCard(
        canvas: Canvas, width: Int, height: Int, plan: Plan, f: Int, shown: PlannedMoment, photos: PhotoSource, top: Float,
        place: (Double, Double, FloatArray) -> Unit, pin: Float, shortSide: Float,
    ) {
        val moment = shown.moment
        val frames = (shown.endFrame - shown.startFrame).coerceAtLeast(1)
        val morph = min(MORPH_SECONDS * plan.fps, frames * 0.3f)
        val t = (f - shown.startFrame).toFloat()
        // 0 is the pin on the map, 1 the card held up in front of it.
        val open = when {
            t < morph -> ease(t / morph)
            t > frames - morph -> ease((frames - t) / morph)
            else -> 1f
        }
        // A clip plays while held up; a photo is its one frame.
        val index = if (moment.frames > 1 && moment.fps > 0) {
            (((t - morph) / plan.fps) * moment.fps).toInt().coerceIn(0, moment.frames - 1)
        } else {
            0
        }
        val bitmap = photos.frame(moment.id, index) ?: if (index > 0) photos.frame(moment.id, 0) else null
        val aspect = if (bitmap != null && bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 4f / 3

        // Dim the map a little behind the card.
        paper.color = (((SCRIM_ALPHA * open).toInt() shl 24))
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paper)

        // Where the card ends up: as large as fits under the title.
        val maxWidth = width * 0.84f
        val maxHeight = (height - top) * 0.64f
        val cardWidth = min(maxWidth, maxHeight * aspect)
        val cardHeight = cardWidth / aspect
        val cardX = width / 2f
        val cardY = top + (height - top) * 0.5f
        place(plan.route.x[shown.point], plan.route.y[shown.point], at)
        val pinBox = pinFrame(at[0], at[1], pin)
        val centerX = lerp(pinBox.centerX(), cardX, open)
        val centerY = lerp(pinBox.centerY(), cardY, open)
        val halfWidth = lerp(pin / 2, cardWidth / 2, open)
        val halfHeight = lerp(pin / 2, cardHeight / 2, open)
        target.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
        drawPicture(canvas, bitmap, target, 1f - open, open, shortSide)
    }

    /**
     * Draws [bitmap] in [box] on a white mount: round as a pin when [round] is 1, a card with
     * slightly rounded corners when 0. [full] runs from a square crop to the whole picture.
     */
    private fun drawPicture(canvas: Canvas, bitmap: Bitmap?, box: RectF, round: Float, full: Float, shortSide: Float) {
        val side = min(box.width(), box.height())
        val radius = lerp(shortSide * 0.018f, side / 2, round)
        val edge = lerp(shortSide * 0.016f, max(2f, side * 0.07f), round)
        border.set(box.left - edge, box.top - edge, box.right + edge, box.bottom + edge)

        paper.color = WHITE
        paper.setShadowLayer(edge * 2.5f, 0f, edge * 0.8f, SHADOW)
        shape.rewind()
        shape.addRoundRect(border, radius + edge, radius + edge, Path.Direction.CW)
        canvas.drawPath(shape, paper)
        paper.clearShadowLayer()

        shape.rewind()
        shape.addRoundRect(box, radius, radius, Path.Direction.CW)
        if (bitmap == null) {
            paper.color = EMPTY
            canvas.drawPath(shape, paper)
            return
        }
        // From the middle square of the picture to all of it.
        val squareSide = min(bitmap.width, bitmap.height)
        val cropWidth = lerp(squareSide.toFloat(), bitmap.width.toFloat(), full)
        val cropHeight = lerp(squareSide.toFloat(), bitmap.height.toFloat(), full)
        // Keep the crop the box's shape, so the picture isn't squashed on the way.
        val boxAspect = box.width() / box.height()
        val width = min(cropWidth, cropHeight * boxAspect)
        val height = width / boxAspect
        val left = ((bitmap.width - width) / 2).toInt()
        val top = ((bitmap.height - height) / 2).toInt()
        source.set(left, top, left + width.toInt(), top + height.toInt())
        val saved = canvas.save()
        canvas.clipPath(shape)
        canvas.drawBitmap(bitmap, source, box, picture)
        canvas.restoreToCount(saved)
    }

    /** The round picture of a pin standing just above map point ([x], [y]). */
    private fun pinFrame(x: Float, y: Float, size: Float): RectF {
        val centerY = y - size * 0.75f
        return RectF(x - size / 2, centerY - size / 2, x + size / 2, centerY + size / 2)
    }

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    private companion object {
        /** Pin size, as a share of the frame's short side. */
        const val PIN = 0.085f

        /** Seconds a card takes to grow out of its pin, and to shrink back. */
        const val MORPH_SECONDS = 0.4f

        const val SCRIM_ALPHA = 70
        const val WHITE = 0xFFFFFFFF.toInt()
        const val EMPTY = 0xFFE4E4E4.toInt()
        const val SHADOW = 0x55000000
    }
}
