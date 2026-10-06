package io.github.hansoda.trace.render

import android.graphics.Bitmap
import kotlin.math.min

/** Supplies map tiles to the renderer. Returning null leaves the tile blank. */
fun interface TileSource {
    fun tile(key: TileKey): Bitmap?
}

/** How the route looks. */
data class Look(
    val map: MapStyle,
    val labels: Boolean,
    val routeColor: Int,
    /** Line width multiplier: 1 is regular. */
    val lineWidth: Float,
    val showPoints: Boolean,
)

/** Text drawn over the map. Formatting is supplied by the caller so it can follow the locale. */
class Overlay(
    val title: String?,
    val showDate: Boolean,
    val showDistance: Boolean,
    val rangeLabel: String,
    val formatDate: (time: Long, offsetMinutes: Short) -> String,
    val formatDistance: (meters: Double) -> String,
) {
    val hasSubtitle: Boolean get() = showDate || showDistance

    companion object {
        val NONE = Overlay(null, false, false, "", { _, _ -> "" }, { "" })
    }
}

/** Sizes of the text block, as shares of the frame, shared with the camera planner. */
object OverlayLayout {
    private const val MARGIN = 0.055f
    private const val TITLE = 0.052f
    private const val SUBTITLE = 0.033f

    fun margin(width: Int, height: Int) = min(width, height) * MARGIN
    fun titleSize(width: Int, height: Int) = min(width, height) * TITLE
    fun subtitleSize(width: Int, height: Int) = min(width, height) * SUBTITLE

    /** Share of the frame height the text block covers, for keeping the route out from under it. */
    fun topInset(aspect: Double, hasTitle: Boolean, hasSubtitle: Boolean): Double {
        if (!hasTitle && !hasSubtitle) return 0.0
        val shortSide = min(aspect, 1.0) // in units of the frame height
        var bottom = MARGIN.toDouble()
        if (hasTitle) bottom += TITLE * 1.2
        if (hasSubtitle) bottom += SUBTITLE * (if (hasTitle) 1.6 else 1.25)
        return bottom * shortSide
    }
}
