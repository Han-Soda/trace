package io.github.hansoda.trace.settings

import android.content.Context
import io.github.hansoda.trace.motion.CameraDistance
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.route.DaySelection
import java.util.Locale

/** Keeps [TraceSettings] in shared preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): TraceSettings {
        val defaults = TraceSettings()
        return TraceSettings(
            days = DaySelection.decode(prefs.getString(DAYS, null)) ?: legacyRange(),
            camera = CameraMode.fromId(prefs.getString(CAMERA, null)).let {
                // Follow was the default before Lock on existed; settings from then start on Lock on.
                if (it == CameraMode.FOLLOW && !prefs.contains(CAMERA_LAG)) CameraMode.TRACK else it
            },
            cameraDistance = CameraDistance.fromId(prefs.getString(CAMERA_DISTANCE, null)),
            cameraLag = prefs.getFloat(CAMERA_LAG, defaults.cameraLag).coerceIn(0f, 1f),
            smoothness = prefs.getFloat(SMOOTHNESS, defaults.smoothness).coerceIn(0f, 1f),
            pauseAtStops = prefs.getBoolean(PAUSE, defaults.pauseAtStops),
            pointsFraction = prefs.getFloat(POINTS, defaults.pointsFraction).coerceIn(0f, 1f),
            style = MapStyle.fromId(prefs.getString(STYLE, null)),
            labels = prefs.getBoolean(LABELS, defaults.labels),
            routeColor = prefs.getInt(COLOR, defaults.routeColor),
            customColor = if (prefs.contains(CUSTOM_COLOR)) prefs.getInt(CUSTOM_COLOR, 0) else null,
            lineWidth = LineWidth.fromId(prefs.getString(LINE, null)),
            showPoints = prefs.getBoolean(POINTS_SHOWN, defaults.showPoints),
            showTitle = prefs.getBoolean(TITLE_SHOWN, defaults.showTitle),
            title = prefs.getString(TITLE, "").orEmpty(),
            showDate = prefs.getBoolean(DATE_SHOWN, defaults.showDate),
            showDistance = prefs.getBoolean(DISTANCE_SHOWN, defaults.showDistance),
            format = VideoFormat.fromId(prefs.getString(FORMAT, null)),
            durationSeconds = prefs.getInt(SECONDS, defaults.durationSeconds)
                .coerceIn(TraceSettings.MIN_SECONDS, TraceSettings.MAX_SECONDS),
            quality = Quality.fromId(prefs.getString(QUALITY, null)),
            fps = prefs.getInt(FPS, defaults.fps).let { if (it == 60) 60 else 30 },
            units = Units.fromId(prefs.getString(UNITS, null), Locale.getDefault().country),
            cartoKey = prefs.getString(CARTO_KEY, "").orEmpty(),
        )
    }

    /** Dates saved by the first version, which only knew single ranges. */
    private fun legacyRange(): DaySelection? {
        val start = prefs.getLong(RANGE_START, Long.MIN_VALUE)
        val end = prefs.getLong(RANGE_END, Long.MIN_VALUE)
        return if (start == Long.MIN_VALUE || end == Long.MIN_VALUE) null else DaySelection.range(start, end)
    }

    fun save(settings: TraceSettings) {
        prefs.edit().apply {
            remove(RANGE_START)
            remove(RANGE_END)
            if (settings.days != null) putString(DAYS, settings.days.encode()) else remove(DAYS)
            putString(CAMERA, settings.camera.id)
            putString(CAMERA_DISTANCE, settings.cameraDistance.id)
            putFloat(CAMERA_LAG, settings.cameraLag)
            putBoolean(PAUSE, settings.pauseAtStops)
            putFloat(SMOOTHNESS, settings.smoothness)
            putFloat(POINTS, settings.pointsFraction)
            putString(STYLE, settings.style.id)
            putBoolean(LABELS, settings.labels)
            putInt(COLOR, settings.routeColor)
            if (settings.customColor != null) putInt(CUSTOM_COLOR, settings.customColor) else remove(CUSTOM_COLOR)
            putString(LINE, settings.lineWidth.id)
            putBoolean(POINTS_SHOWN, settings.showPoints)
            putBoolean(TITLE_SHOWN, settings.showTitle)
            putString(TITLE, settings.title)
            putBoolean(DATE_SHOWN, settings.showDate)
            putBoolean(DISTANCE_SHOWN, settings.showDistance)
            putString(FORMAT, settings.format.id)
            putInt(SECONDS, settings.durationSeconds)
            putString(QUALITY, settings.quality.id)
            putInt(FPS, settings.fps)
            putString(UNITS, settings.units.id)
            putString(CARTO_KEY, settings.cartoKey.trim())
        }.apply()
    }

    private companion object {
        const val RANGE_START = "range_start"
        const val RANGE_END = "range_end"
        const val DAYS = "days"
        const val CAMERA = "camera"
        const val CAMERA_DISTANCE = "camera_distance"
        const val CAMERA_LAG = "camera_lag"
        const val PAUSE = "pause_at_stops"
        const val SMOOTHNESS = "smoothness"
        const val POINTS = "points"
        const val STYLE = "style"
        const val LABELS = "labels"
        const val COLOR = "color"
        const val CUSTOM_COLOR = "custom_color"
        const val LINE = "line"
        const val POINTS_SHOWN = "points_shown"
        const val TITLE_SHOWN = "title_shown"
        const val TITLE = "title"
        const val DATE_SHOWN = "date_shown"
        const val DISTANCE_SHOWN = "distance_shown"
        const val FORMAT = "format"
        const val SECONDS = "seconds"
        const val QUALITY = "quality"
        const val FPS = "fps"
        const val UNITS = "units"
        const val CARTO_KEY = "carto_key"
    }
}
