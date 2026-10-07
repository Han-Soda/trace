package io.github.hansoda.trace.settings

import io.github.hansoda.trace.motion.CameraDistance
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.route.PointBudget

enum class VideoFormat(val id: String, private val across: Int, private val down: Int) {
    VERTICAL("9:16", 9, 16),
    PORTRAIT("4:5", 4, 5),
    SQUARE("1:1", 1, 1),
    WIDE("16:9", 16, 9),
    ;

    val aspect: Double get() = across.toDouble() / down

    /** Pixel size with the short side at [shortSide], rounded to even numbers for the encoder. */
    fun size(shortSide: Int): Pair<Int, Int> {
        val long = (shortSide.toLong() * maxOf(across, down) / minOf(across, down)).toInt() and 1.inv()
        return if (across <= down) shortSide to long else long to shortSide
    }

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: VERTICAL
    }
}

enum class Quality(val id: String, val shortSide: Int) {
    HD("720p", 720),
    FULL_HD("1080p", 1080),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: FULL_HD
    }
}

enum class LineWidth(val id: String, val factor: Float) {
    THIN("thin", 0.6f),
    REGULAR("regular", 1f),
    BOLD("bold", 1.7f),
    ;

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: REGULAR
    }
}

enum class Units(val id: String) {
    KILOMETERS("km"),
    MILES("mi"),
    ;

    companion object {
        fun fromId(id: String?, country: String): Units =
            entries.firstOrNull { it.id == id } ?: if (country in MILE_COUNTRIES) MILES else KILOMETERS

        private val MILE_COUNTRIES = setOf("US", "GB", "LR", "MM")
    }
}

/** Route colours offered as swatches. */
object Palette {
    val colors: List<Int> = listOf(
        0xFFFF5A36, 0xFFFFA41B, 0xFFFFD60A, 0xFF2EC4A6, 0xFF2D7FF9,
        0xFF6C5CE7, 0xFFE040A0, 0xFF1B1B1F, 0xFFFFFFFF,
    ).map { it.toInt() }
    val DEFAULT: Int = colors.first()
}

/** Everything the person chooses, remembered between launches. */
data class TraceSettings(
    /** Chosen days, or null for the latest week. */
    val days: DaySelection? = null,
    val camera: CameraMode = CameraMode.TRACK,
    val cameraDistance: CameraDistance = CameraDistance.MEDIUM,
    /** How far a lock-on or heading-up camera trails the dot before catching up, 0–1. */
    val cameraLag: Float = 0.3f,
    /** Seconds each photo stays on screen; clips play their own length. */
    val photoSeconds: Float = 2f,
    val smoothness: Float = 0.6f,
    /** Off, the dot never stops moving. */
    val pauseAtStops: Boolean = false,
    val pointsFraction: Float = PointBudget.DEFAULT_FRACTION,
    // Paper needs no map key, so the first video looks right straight away.
    val style: MapStyle = MapStyle.PAPER,
    val labels: Boolean = true,
    val routeColor: Int = Palette.DEFAULT,
    /** The last colour picked with the colour picker, shown as an extra swatch. */
    val customColor: Int? = null,
    val lineWidth: LineWidth = LineWidth.REGULAR,
    val showPoints: Boolean = false,
    val showTitle: Boolean = true,
    val title: String = "",
    val showDate: Boolean = true,
    val showDistance: Boolean = true,
    val format: VideoFormat = VideoFormat.VERTICAL,
    val durationSeconds: Int = 20,
    val quality: Quality = Quality.FULL_HD,
    val fps: Int = 30,
    val units: Units = Units.KILOMETERS,
    val cartoKey: String = "",
) {
    companion object {
        const val MIN_SECONDS = 6
        const val MAX_SECONDS = 60
    }
}
