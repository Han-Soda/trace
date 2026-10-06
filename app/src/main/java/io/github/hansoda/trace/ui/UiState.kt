package io.github.hansoda.trace.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
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
    val startDay: Long,
    val endDay: Long,
    val firstDay: Long,
    val lastDay: Long,
    val distance: String,
    val stops: Int,
    val availablePoints: Int,
    val selectedPoints: Int,
) {
    val days: Int get() = (endDay - startDay + 1).toInt()
    val canGoBack: Boolean get() = startDay > firstDay
    val canGoForward: Boolean get() = endDay < lastDay
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
)

/** Everything the screen can ask for. */
class ScreenActions(
    val openFiles: () -> Unit,
    val update: ((TraceSettings) -> TraceSettings) -> Unit,
    val preset: (RangePreset) -> Unit,
    val shiftRange: (Int) -> Unit,
    val setRange: (startDay: Long, endDay: Long) -> Unit,
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
