package io.github.hansoda.trace.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.hansoda.trace.route.DayActivity
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.settings.TraceSettings

enum class RangePreset { DAY, WEEK, MONTH, YEAR, ALL }

sealed interface ImportState {
    data object Idle : ImportState
    data class Reading(val progress: Float) : ImportState
    data class Failed(val message: String) : ImportState
}

sealed interface ExportState {
    data object Idle : ExportState
    data class Running(val image: Boolean, val stage: Stage, val progress: Float) : ExportState
    data class Done(val image: Boolean) : ExportState
    data class Failed(val message: String) : ExportState

    enum class Stage { PREPARING, MAP, FRAMES, SAVING }
}

sealed interface KeyTest {
    data object Idle : KeyTest
    data object Testing : KeyTest
    data class Loaded(val tile: ImageBitmap) : KeyTest
    data object Failed : KeyTest
}

/** The chosen dates and what they hold. */
@Immutable
data class RangeSummary(
    val label: String,
    val preset: RangePreset?,
    val selection: DaySelection,
    /** First and last day with location history. */
    val firstDay: Long,
    val lastDay: Long,
    val distance: String,
    val stops: Int,
    val availablePoints: Int,
    val selectedPoints: Int,
) {
    val days: Int get() = selection.dayCount
    val canGoBack: Boolean get() = selection.first > firstDay
    val canGoForward: Boolean get() = selection.last < lastDay
    val empty: Boolean get() = availablePoints < 2
}

@Immutable
data class ScreenState(
    val loading: Boolean,
    val hasTimeline: Boolean,
    val import: ImportState,
    val settings: TraceSettings,
    val range: RangeSummary?,
    val previewReady: Boolean,
    val export: ExportState,
    val timelineName: String?,
    val timelinePoints: Int,
    val cacheSize: String,
    val keyTest: KeyTest,
    val version: String,
    /** Distance per day, for the calendar; null until worked out. */
    val activity: DayActivity?,
    /** Fixes removed by hand as GPS errors, across the whole history. */
    val removedPoints: Int,
)

/** Everything the screen can ask for. */
class ScreenActions(
    val openFiles: () -> Unit,
    val update: ((TraceSettings) -> TraceSettings) -> Unit,
    val preset: (RangePreset) -> Unit,
    val shiftRange: (Int) -> Unit,
    val setDays: (DaySelection) -> Unit,
    val openTimelineExport: () -> Unit,
    val restorePoints: () -> Unit,
    val exportVideo: () -> Unit,
    val saveImage: () -> Unit,
    val cancelExport: () -> Unit,
    val dismissExport: () -> Unit,
    val shareExport: () -> Unit,
    val openExport: () -> Unit,
    val dismissImportError: () -> Unit,
    val testKey: () -> Unit,
    val openKeyPage: () -> Unit,
    val clearCache: () -> Unit,
    val removeTimeline: () -> Unit,
)
