package io.github.hansoda.trace.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.motion.PlannedMoment
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Draws a video's photos and clips, each in the [PhotoStyle] chosen: it comes up while the dot
 * waits where it was taken, then leaves a pin, or a small print, on the map there.
 */
class MomentPainter {
    private val picture = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG)
    private val words = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shape = Path()
    private val source = Rect()
    private val box = RectF()
    private val mount = RectF()
    private val at = FloatArray(2)

    /**
     * @param top height of the title at the top, which pictures stay clear of.
     * @param place puts the screen position of a map point into its third argument.
     */
    fun draw(
        canvas: Canvas, width: Int, height: Int, plan: Plan, f: Int, photos: PhotoSource, look: Look,
        captions: Map<String, Caption>, top: Float, place: (x: Double, y: Double, out: FloatArray) -> Unit,
    ) {
        val shortSide = min(width, height).toFloat()
        val style = look.photoStyle
        val pin = shortSide * PIN
        // Pins of the moments already shown, a little apart where several share a place. In a
        // corner the pin comes up with the picture, to show where it was taken.
        var previousPoint = -1
        var sharing = 0
        plan.moments.forEachIndexed { k, moment ->
            sharing = if (moment.point == previousPoint) sharing + 1 else 0
            previousPoint = moment.point
            val showing = f in moment.startFrame..moment.endFrame
            val grow = when {
                f > moment.endFrame -> 1f
                showing && style == PhotoStyle.CORNER -> min(1f, (f - moment.startFrame) / moment.openFrames.coerceAtLeast(1f))
                else -> return@forEachIndexed
            }
            place(plan.route.x[moment.point], plan.route.y[moment.point], at)
            val x = at[0] + sharing * pin * 0.4f
            if (x < -pin * 2 || x > width + pin * 2 || at[1] < -pin * 2 || at[1] > height + pin * 3) return@forEachIndexed
            val thumbnail = photos.thumbnail(moment.moment.id)
            if (style == PhotoStyle.POLAROID) {
                drawPrint(canvas, thumbnail, null, x, at[1], shortSide * SMALL_PRINT * ease(grow), tilt(k), 1f)
            } else {
                drawPin(canvas, thumbnail, x, at[1], pin * ease(grow))
            }
        }

        val k = plan.moments.indexOfFirst { f in it.startFrame..it.endFrame }
        if (k < 0) return
        val shown = plan.moments[k]
        val open = openness(shown, f)
        val moment = shown.moment
        val index = clipFrame(shown, f, plan.fps)
        val bitmap = photos.frame(moment.id, index, moment.frames) ?: if (index > 0) photos.frame(moment.id, 0, moment.frames) else null
        val caption = captions[moment.id]
        place(plan.route.x[shown.point], plan.route.y[shown.point], at)
        when (style) {
            PhotoStyle.CARD -> drawCard(canvas, width, height, bitmap, caption, open, at[0], at[1], pin, top, shortSide)
            PhotoStyle.MAP -> drawStanding(canvas, width, height, bitmap, caption, open, at[0], at[1], pin, top, shortSide)
            PhotoStyle.CORNER -> drawCorner(canvas, width, height, bitmap, caption, open, look.photoCorner, top, shortSide)
            PhotoStyle.POLAROID -> drawPolaroid(canvas, width, height, bitmap, caption, open, at[0], at[1], top, tilt(k), shortSide)
        }
    }

    // region Styles

    /** A large card held up in front of the dimmed map. */
    private fun drawCard(
        canvas: Canvas, width: Int, height: Int, bitmap: Bitmap?, caption: Caption?, open: Float,
        x: Float, y: Float, pin: Float, top: Float, shortSide: Float,
    ) {
        paper.color = (SCRIM_ALPHA * open).toInt() shl 24
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paper)
        val aspect = aspectOf(bitmap)
        val edge = shortSide * 0.016f
        val room = captionHeight(caption, shortSide * 0.034f, shortSide * 0.027f)
        // As large as fits under the title, with room for the caption.
        val cardWidth = min(width * 0.84f, ((height - top) * 0.64f - room) * aspect)
        val cardHeight = cardWidth / aspect
        val centerX = width / 2f
        val centerY = top + (height - top) * 0.5f - room / 2
        grow(x, pinCenter(y, pin), pin, centerX, centerY, cardWidth, cardHeight, open)
        val shown = open * open
        mountAround(edge, room * shown)
        drawMount(canvas, lerp(shortSide * 0.018f + edge, mount.height() / 2, 1 - open), 1f)
        drawImage(canvas, bitmap, box, lerp(shortSide * 0.018f, box.height() / 2, 1 - open), open, 1f)
        if (caption != null && shown > 0.3f) {
            drawCaption(canvas, caption, box.left, box.bottom + edge * 0.4f, box.width(), shortSide * 0.034f, shortSide * 0.027f, dark = true, alpha = (shown - 0.3f) / 0.7f)
        }
    }

    /** A picture standing on the map where it was taken, pointing down at the spot. */
    private fun drawStanding(
        canvas: Canvas, width: Int, height: Int, bitmap: Bitmap?, caption: Caption?, open: Float,
        x: Float, y: Float, pin: Float, top: Float, shortSide: Float,
    ) {
        val aspect = aspectOf(bitmap)
        val long = shortSide * 0.36f
        val pictureWidth = if (aspect >= 1) long else long * aspect
        val pictureHeight = pictureWidth / aspect
        val edge = shortSide * 0.012f
        val placeSize = shortSide * 0.027f
        val timeSize = shortSide * 0.022f
        val room = captionHeight(caption, placeSize, timeSize)
        val tail = shortSide * 0.035f
        val wholeHeight = pictureHeight + 2 * edge + room
        val margin = shortSide * 0.03f
        // Above the spot, or below it when there's no room under the title.
        val above = y - tail - wholeHeight >= top + margin
        val mountTop = if (above) y - tail - wholeHeight else min(y + tail, height - margin - wholeHeight)
        val halfWidth = pictureWidth / 2 + edge
        val centerX = fit(x, margin + halfWidth, width - margin - halfWidth)
        val centerY = mountTop + edge + pictureHeight / 2
        grow(x, pinCenter(y, pin), pin, centerX, centerY, pictureWidth, pictureHeight, open)
        val shown = open * open
        mountAround(edge, room * shown)
        val radius = lerp(shortSide * 0.016f + edge, mount.height() / 2, 1 - open)
        // The point of the speech-bubble tail, on the spot.
        shape.rewind()
        shape.addRoundRect(mount, radius, radius, Path.Direction.CW)
        if (open > 0.05f) {
            val tip = tail * open
            val half = shortSide * 0.028f * open
            val tailX = fit(x, mount.left + radius + half, mount.right - radius - half)
            val base = if (above) mount.bottom - 1 else mount.top + 1
            shape.moveTo(tailX - half, base)
            shape.lineTo(x.coerceIn(tailX - half * 3, tailX + half * 3), if (above) base + tip else base - tip)
            shape.lineTo(tailX + half, base)
            shape.close()
        }
        paper.color = WHITE
        paper.setShadowLayer(edge * 2.5f, 0f, edge * 0.8f, SHADOW)
        canvas.drawPath(shape, paper)
        paper.clearShadowLayer()
        drawImage(canvas, bitmap, box, lerp(shortSide * 0.016f, box.height() / 2, 1 - open), open, 1f)
        if (caption != null && shown > 0.3f) {
            drawCaption(canvas, caption, box.left, box.bottom + edge * 0.3f, box.width(), placeSize, timeSize, dark = true, alpha = (shown - 0.3f) / 0.7f)
        }
    }

    /** A small picture in a corner, the map going on beside it. */
    private fun drawCorner(
        canvas: Canvas, width: Int, height: Int, bitmap: Bitmap?, caption: Caption?, open: Float,
        corner: Corner, top: Float, shortSide: Float,
    ) {
        if (open <= 0.01f) return
        val aspect = aspectOf(bitmap)
        val side = shortSide * 0.42f
        val fullWidth = if (aspect >= 1) side else side * aspect
        val fullHeight = fullWidth / aspect
        val margin = shortSide * 0.045f
        // Clear of the title at the top and of the map's credit at the bottom.
        val left = if (corner.right) width - margin - fullWidth else margin
        val upper = if (corner.bottom) height - shortSide * 0.045f - margin * 0.6f - fullHeight else max(top, margin * 0.5f) + margin * 0.5f
        // Grows a little out of its corner as it fades in.
        val scale = 0.82f + 0.18f * open
        val anchorX = if (corner.right) left + fullWidth else left
        val anchorY = if (corner.bottom) upper + fullHeight else upper
        box.set(
            anchorX + (left - anchorX) * scale, anchorY + (upper - anchorY) * scale,
            anchorX + (left + fullWidth - anchorX) * scale, anchorY + (upper + fullHeight - anchorY) * scale,
        )
        val edge = shortSide * 0.006f
        mount.set(box.left - edge, box.top - edge, box.right + edge, box.bottom + edge)
        val radius = shortSide * 0.022f
        drawMount(canvas, radius + edge, open)
        drawImage(canvas, bitmap, box, radius, 1f, open)
        if (caption != null) {
            val placeSize = shortSide * 0.028f
            val timeSize = shortSide * 0.022f
            val room = captionHeight(caption, placeSize, timeSize)
            // Light words on a dark band across the bottom of the picture.
            val saved = canvas.save()
            shape.rewind()
            shape.addRoundRect(box, radius, radius, Path.Direction.CW)
            canvas.clipPath(shape)
            paper.color = ((0x8C * open).toInt() shl 24)
            canvas.drawRect(box.left, box.bottom - room, box.right, box.bottom, paper)
            canvas.restoreToCount(saved)
            drawCaption(canvas, caption, box.left + radius * 0.4f, box.bottom - room, box.width() - radius * 0.8f, placeSize, timeSize, dark = false, alpha = open)
        }
    }

    /** A white-bordered print dropped near the spot, tilted, that stays there small. */
    private fun drawPolaroid(
        canvas: Canvas, width: Int, height: Int, bitmap: Bitmap?, caption: Caption?, open: Float,
        x: Float, y: Float, top: Float, tilt: Float, shortSide: Float,
    ) {
        val full = shortSide * 0.44f
        val printWidth = lerp(shortSide * SMALL_PRINT, full, open)
        val margin = shortSide * 0.04f
        // From the small print on the spot to the big one, up above it where there's room.
        val bigBottom = if (y - full * PRINT_HEIGHT - shortSide * 0.02f >= top + margin) y - shortSide * 0.02f else min(height - margin, y + full * PRINT_HEIGHT + shortSide * 0.02f)
        val bottom = lerp(y, bigBottom, open)
        val centerX = lerp(x, fit(x, margin + full / 2, width - margin - full / 2), open)
        drawPrint(canvas, bitmap, caption, centerX, bottom, printWidth, tilt, 1f, captionShown = open)
    }

    // endregion

    // region Pieces

    /** A round picture on a white ring, standing just above map point ([x], [y]). */
    private fun drawPin(canvas: Canvas, bitmap: Bitmap?, x: Float, y: Float, size: Float) {
        if (size <= 0.5f) return
        val center = pinCenter(y, size)
        box.set(x - size / 2, center - size / 2, x + size / 2, center + size / 2)
        val edge = max(2f, size * 0.07f)
        mount.set(box.left - edge, box.top - edge, box.right + edge, box.bottom + edge)
        drawMount(canvas, size / 2 + edge, 1f)
        drawImage(canvas, bitmap, box, size / 2, 0f, 1f)
    }

    /**
     * A print [printWidth] wide whose bottom middle is at ([x], [bottom]), tilted [tilt] degrees,
     * with the [caption] on its wide bottom border once [captionShown] is past half.
     */
    private fun drawPrint(
        canvas: Canvas, bitmap: Bitmap?, caption: Caption?, x: Float, bottom: Float, printWidth: Float,
        tilt: Float, alpha: Float, captionShown: Float = 0f,
    ) {
        if (printWidth <= 1f) return
        val printHeight = printWidth * PRINT_HEIGHT
        val saved = canvas.save()
        canvas.rotate(tilt, x, bottom - printHeight / 2)
        mount.set(x - printWidth / 2, bottom - printHeight, x + printWidth / 2, bottom)
        val border = printWidth * PRINT_BORDER
        box.set(mount.left + border, mount.top + border, mount.right - border, mount.top + border + (printWidth - 2 * border))
        drawMount(canvas, printWidth * 0.012f, alpha)
        drawImage(canvas, bitmap, box, printWidth * 0.004f, 0f, alpha)
        if (caption != null && captionShown > 0.5f) {
            val placeSize = printWidth * 0.068f
            val timeSize = printWidth * 0.052f
            val room = mount.bottom - box.bottom
            val lines = captionHeight(caption, placeSize, timeSize)
            drawCaption(
                canvas, caption, box.left, box.bottom + (room - lines) / 2, box.width(), placeSize, timeSize,
                dark = true, alpha = (captionShown - 0.5f) * 2, centered = true,
            )
        }
        canvas.restoreToCount(saved)
    }

    /** The white mount around [mount], with a soft shadow. */
    private fun drawMount(canvas: Canvas, radius: Float, alpha: Float) {
        paper.color = WHITE
        paper.alpha = (255 * alpha).toInt()
        paper.setShadowLayer(min(mount.width(), mount.height()) * 0.06f + 2f, 0f, min(mount.width(), mount.height()) * 0.02f, SHADOW)
        shape.rewind()
        shape.addRoundRect(mount, radius, radius, Path.Direction.CW)
        canvas.drawPath(shape, paper)
        paper.clearShadowLayer()
    }

    /**
     * Draws [bitmap] in [box] with [radius] corners: [full] runs from the middle square of the
     * picture to all of it. A missing picture is a grey placeholder.
     */
    private fun drawImage(canvas: Canvas, bitmap: Bitmap?, box: RectF, radius: Float, full: Float, alpha: Float) {
        shape.rewind()
        shape.addRoundRect(box, radius, radius, Path.Direction.CW)
        if (bitmap == null) {
            paper.color = EMPTY
            paper.alpha = (255 * alpha).toInt()
            canvas.drawPath(shape, paper)
            return
        }
        val squareSide = min(bitmap.width, bitmap.height)
        val cropWidth = lerp(squareSide.toFloat(), bitmap.width.toFloat(), full)
        val cropHeight = lerp(squareSide.toFloat(), bitmap.height.toFloat(), full)
        // Keep the crop the box's shape, so the picture isn't squashed on the way.
        val boxAspect = box.width() / box.height().coerceAtLeast(1f)
        val width = min(cropWidth, cropHeight * boxAspect)
        val height = width / boxAspect
        val left = ((bitmap.width - width) / 2).toInt()
        val top = ((bitmap.height - height) / 2).toInt()
        source.set(left, top, left + width.toInt(), top + height.toInt())
        picture.alpha = (255 * alpha).toInt()
        val saved = canvas.save()
        canvas.clipPath(shape)
        canvas.drawBitmap(bitmap, source, box, picture)
        canvas.restoreToCount(saved)
    }

    /** Height of a caption's lines and the space around them. */
    private fun captionHeight(caption: Caption?, placeSize: Float, timeSize: Float): Float {
        if (caption == null) return 0f
        val pad = placeSize * 0.55f
        return pad * 2 + (if (caption.place != null) placeSize * 1.2f else 0f) + timeSize * 1.2f
    }

    /** The place in bold over the time, from ([left], [top]) and no wider than [width]. */
    private fun drawCaption(
        canvas: Canvas, caption: Caption, left: Float, top: Float, width: Float, placeSize: Float, timeSize: Float,
        dark: Boolean, alpha: Float, centered: Boolean = false,
    ) {
        val fade = alpha.coerceIn(0f, 1f)
        if (fade <= 0f) return
        var baseline = top + placeSize * 0.55f
        caption.place?.let { place ->
            words.typeface = PLACE_FACE
            words.textSize = placeSize
            words.color = if (dark) PLACE_INK else 0xFFFFFFFF.toInt()
            words.alpha = (255 * fade).toInt()
            baseline += placeSize * 0.95f
            val text = ellipsize(place, width)
            canvas.drawText(text, if (centered) left + (width - words.measureText(text)) / 2 else left, baseline, words)
            baseline += placeSize * 0.25f
        }
        words.typeface = Typeface.DEFAULT
        words.textSize = timeSize
        words.color = if (dark) TIME_INK else 0xE6FFFFFF.toInt()
        words.alpha = ((if (dark) 255 else 230) * fade).toInt()
        baseline += timeSize * 0.95f
        val text = ellipsize(caption.time, width)
        canvas.drawText(text, if (centered) left + (width - words.measureText(text)) / 2 else left, baseline, words)
    }

    private fun ellipsize(text: String, maxWidth: Float): String {
        if (words.measureText(text) <= maxWidth) return text
        val fits = words.breakText(text, true, maxWidth - words.measureText("…"), null)
        return text.substring(0, fits.coerceAtLeast(0)).trimEnd() + "…"
    }

    // endregion

    // region Geometry and time

    /** Sets [box] between the pin around ([pinX], [pinY]) and the picture around ([x], [y]). */
    private fun grow(pinX: Float, pinY: Float, pin: Float, x: Float, y: Float, width: Float, height: Float, open: Float) {
        val centerX = lerp(pinX, x, open)
        val centerY = lerp(pinY, y, open)
        val halfWidth = lerp(pin / 2, width / 2, open)
        val halfHeight = lerp(pin / 2, height / 2, open)
        box.set(centerX - halfWidth, centerY - halfHeight, centerX + halfWidth, centerY + halfHeight)
    }

    /** Sets [mount] to [box] with an [edge] all round and [below] more at the bottom, for words. */
    private fun mountAround(edge: Float, below: Float) {
        mount.set(box.left - edge, box.top - edge, box.right + edge, box.bottom + edge + below)
    }

    private fun pinCenter(y: Float, size: Float) = y - size * 0.75f

    /** [value] between [low] and [high], or halfway when there's no room between them. */
    private fun fit(value: Float, low: Float, high: Float): Float = if (low > high) (low + high) / 2 else value.coerceIn(low, high)

    private fun aspectOf(bitmap: Bitmap?): Float =
        if (bitmap != null && bitmap.height > 0) (bitmap.width.toFloat() / bitmap.height).coerceIn(0.4f, 2.5f) else 4f / 3

    /** 0 while the picture is a pin, 1 while it's fully up. */
    private fun openness(shown: PlannedMoment, f: Int): Float {
        val frames = (shown.endFrame - shown.startFrame).coerceAtLeast(1)
        val t = (f - shown.startFrame).toFloat()
        val open = shown.openFrames.coerceAtLeast(1f)
        return when {
            t < open -> ease(t / open)
            t > frames - open -> ease((frames - t) / open)
            else -> 1f
        }
    }

    /** The clip frame on screen: a clip plays at its own pace once it's up, then holds its last frame. */
    private fun clipFrame(shown: PlannedMoment, f: Int, fps: Int): Int {
        val moment = shown.moment
        if (moment.frames <= 1 || moment.fps <= 0) return 0
        val seconds = (f - shown.startFrame - shown.openFrames) / fps
        return (seconds * moment.fps).toInt().coerceIn(0, moment.frames - 1)
    }

    /** Prints lie at slightly different angles, alternately left and right. */
    private fun tilt(k: Int): Float = (if (k % 2 == 0) -1 else 1) * (3f + abs((k * 7919) % 4))

    private fun ease(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * x * (x * (x * 6 - 15) + 10)
    }

    private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

    // endregion

    private companion object {
        /** Pin size, as a share of the frame's short side. */
        const val PIN = 0.085f

        /** Width of the print a polaroid leaves on the map, as a share of the short side. */
        const val SMALL_PRINT = 0.1f

        /** A print's height for its width, its side border, and the picture is square. */
        const val PRINT_HEIGHT = 1.2f
        const val PRINT_BORDER = 0.06f

        const val SCRIM_ALPHA = 70
        const val WHITE = 0xFFFFFFFF.toInt()
        const val EMPTY = 0xFFE4E4E4.toInt()
        const val SHADOW = 0x55000000
        const val PLACE_INK = 0xFF1B1B1F.toInt()
        const val TIME_INK = 0xFF5F6368.toInt()
        val PLACE_FACE: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
