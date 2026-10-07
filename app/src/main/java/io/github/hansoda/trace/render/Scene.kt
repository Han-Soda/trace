package io.github.hansoda.trace.render

import android.graphics.Bitmap
import kotlin.math.min

/** Supplies map tiles to the renderer. Returning null leaves the tile blank. */
fun interface TileSource {
    fun tile(key: TileKey): Bitmap?
}

/** Supplies the pictures of photos and clips on the route. Returning null draws a blank card. */
interface PhotoSource {
    /** Frame [index] of a clip of [frames] frames, or the photo itself for 0 of 1. */
    fun frame(id: String, index: Int, frames: Int): Bitmap?

    /** A small square picture, for the pin left on the map. */
    fun thumbnail(id: String): Bitmap?
}

/** How the route looks. */
data class Look(
    val map: MapStyle,
    val labels: Boolean,
    val routeColor: Int,
    /** Line width multiplier: 1 is regular. */
    val lineWidth: Float,
    val showPoints: Boolean,
    val photoStyle: PhotoStyle = PhotoStyle.CARD,
    /** Where [PhotoStyle.CORNER] shows photos. */
    val photoCorner: Corner = Corner.BOTTOM_RIGHT,
)

/** How photos and clips come up on the video. Each leaves a mark on the map where it was taken. */
enum class PhotoStyle(val id: String) {
    /** A large card in front of the map, which then shrinks into a pin. */
    CARD("card"),

    /** A picture standing on the map where it was taken, a little larger than the pin it becomes. */
    MAP("map"),

    /** A small picture in a corner of the video, the map carrying on beside it. */
    CORNER("corner"),

    /** A print with a white border, dropped onto the map where it was taken and left there. */
    POLAROID("polaroid"),
    ;

    companion object {
        fun fromId(id: String?): PhotoStyle = entries.firstOrNull { it.id == id } ?: CARD
    }
}

enum class Corner(val id: String, val right: Boolean, val bottom: Boolean) {
    TOP_LEFT("top_left", false, false),
    TOP_RIGHT("top_right", true, false),
    BOTTOM_LEFT("bottom_left", false, true),
    BOTTOM_RIGHT("bottom_right", true, true),
    ;

    companion object {
        fun fromId(id: String?): Corner = entries.firstOrNull { it.id == id } ?: BOTTOM_RIGHT
    }
}

/** The words that go with a photo: where it was taken, when known, and when. */
data class Caption(val place: String?, val time: String)

/** Text drawn over the map. Formatting is supplied by the caller so it can follow the locale. */
class Overlay(
    val title: String?,
    val showDate: Boolean,
    val showDistance: Boolean,
    val rangeLabel: String,
    val formatDate: (time: Long, offsetMinutes: Short) -> String,
    val formatDistance: (meters: Double) -> String,
    /** Captions of photos and clips, by id; those without one show none. */
    val captions: Map<String, Caption> = emptyMap(),
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
