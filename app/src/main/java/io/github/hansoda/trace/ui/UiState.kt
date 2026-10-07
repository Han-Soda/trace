package io.github.hansoda.trace.ui

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.ImageBitmap
import io.github.hansoda.trace.route.DayActivity
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.settings.AppLanguage
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

/** Adding photos and videos: under way, or what came of it. */
sealed interface MediaAdding {
    data class Working(val done: Int, val total: Int) : MediaAdding

    data class Finished(val added: Int, val undated: Int, val unreadable: Int, val otherDays: Int) : MediaAdding
}

/** A photo or clip on the chosen days, as the screen lists it. */
@Immutable
data class PhotoCard(
    val id: String,
    val isClip: Boolean,
    val seconds: Double,
    val thumbnail: ImageBitmap?,
    /** The video a clip can be trimmed from again, when Trace can still read it. */
    val source: String? = null,
    val sourceMs: Long = 0,
    val startMs: Long = 0,
    val audio: Boolean = false,
)

/** The photos and clips on the chosen days, and how many others are kept. */
@Immutable
data class PhotosSummary(val cards: List<PhotoCard>, val otherDays: Int) {
    val hasClips: Boolean get() = cards.any { it.isClip }
    val hasSound: Boolean get() = cards.any { it.audio }

    companion object {
        val EMPTY = PhotosSummary(emptyList(), 0)
    }
}

/** A photo or video in the gallery, from the chosen days. */
@Immutable
data class FoundMedia(val uri: Uri, val time: Long, val video: Boolean, val durationMs: Long, val day: String = "")

/** Looking through the gallery for the chosen days, or what turned up. */
sealed interface MediaFinding {
    data object Searching : MediaFinding

    /** Trace wasn't let in to the gallery. */
    data object Denied : MediaFinding

    /** What was found, oldest first, and an even spread of it to start with. */
    data class Found(val items: List<FoundMedia>, val suggested: Set<Uri>) : MediaFinding
}

/** Keeping another part of a clip's video. */
sealed interface ClipTrim {
    val id: String

    data class Working(override val id: String, val progress: Float) : ClipTrim

    data class Failed(override val id: String) : ClipTrim
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
    val language: AppLanguage,
    val photos: PhotosSummary,
    val mediaAdding: MediaAdding?,
    val finding: MediaFinding? = null,
    val trimming: ClipTrim? = null,
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
    val openPrivacyPolicy: () -> Unit,
    val clearCache: () -> Unit,
    val removeTimeline: () -> Unit,
    val setLanguage: (AppLanguage) -> Unit,
    val addMedia: () -> Unit,
    val removeMedia: (String) -> Unit,
    val dismissMediaNote: () -> Unit,
    /** Asks for the gallery if need be, then looks through it for the chosen days. */
    val findMedia: () -> Unit,
    val addFound: (List<Uri>) -> Unit,
    val dismissFinding: () -> Unit,
    val foundThumbnail: suspend (FoundMedia) -> ImageBitmap?,
    val trimClip: (id: String, startMs: Long, lengthMs: Long) -> Unit,
    val dismissTrimError: () -> Unit,
)
