package io.github.hansoda.trace

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.stream.MalformedJsonException
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.data.TimelineFormatException
import io.github.hansoda.trace.data.TimelineInfo
import io.github.hansoda.trace.data.TimelineRepository
import io.github.hansoda.trace.export.VideoExporter
import io.github.hansoda.trace.export.VideoSizes
import io.github.hansoda.trace.motion.CameraDistance
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.motion.MotionSettings
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.motion.Planner
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.Overlay
import io.github.hansoda.trace.render.OverlayLayout
import io.github.hansoda.trace.route.DayActivity
import io.github.hansoda.trace.route.DaySelection
import io.github.hansoda.trace.route.Exclusions
import io.github.hansoda.trace.route.PointBudget
import io.github.hansoda.trace.route.RangeData
import io.github.hansoda.trace.route.Route
import io.github.hansoda.trace.route.RouteBuilder
import io.github.hansoda.trace.route.Suspects
import io.github.hansoda.trace.settings.SettingsStore
import io.github.hansoda.trace.settings.TraceSettings
import io.github.hansoda.trace.settings.VideoFormat
import io.github.hansoda.trace.tiles.TileStore
import io.github.hansoda.trace.ui.ExportState
import io.github.hansoda.trace.ui.Formats
import io.github.hansoda.trace.ui.ImportState
import io.github.hansoda.trace.ui.KeyTest
import io.github.hansoda.trace.ui.RangePreset
import io.github.hansoda.trace.ui.RangeSummary
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class TraceViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()
    private val store = SettingsStore(application)
    private val repository = TimelineRepository(application)
    val tiles = TileStore(application)
    private val exporter = VideoExporter(application, tiles)

    private val _settings = MutableStateFlow(store.load())
    val settings: StateFlow<TraceSettings> = _settings.asStateFlow()

    private val timeline = MutableStateFlow<Timeline?>(null)
    private val _info = MutableStateFlow(repository.info())
    val info: StateFlow<TimelineInfo?> = _info.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _import = MutableStateFlow<ImportState>(ImportState.Idle)
    val import: StateFlow<ImportState> = _import.asStateFlow()
    private val _export = MutableStateFlow<ExportState>(ExportState.Idle)
    val export: StateFlow<ExportState> = _export.asStateFlow()
    private val _keyTest = MutableStateFlow<KeyTest>(KeyTest.Idle)
    val keyTest: StateFlow<KeyTest> = _keyTest.asStateFlow()
    private val _cacheBytes = MutableStateFlow(0L)
    val cacheSize: StateFlow<String> = _cacheBytes.map { Formatter.formatShortFileSize(app, it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Fixes removed by hand, and earlier versions for undo. */
    private val _removed = MutableStateFlow(repository.removed())
    val removed: StateFlow<Exclusions> = _removed.asStateFlow()
    private val undo = ArrayDeque<Exclusions>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val removedPoints: StateFlow<Int> = combine(timeline, _removed) { loaded, removed -> loaded?.let { removed.countIn(it) } ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private var importJob: Job? = null
    private var exportJob: Job? = null
    private var exported: Uri? = null

    private val days = timeline.map { it?.let { Formats.dayOf(it.times.first()) to Formats.dayOf(it.times.last()) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Distance travelled each day, to mark the calendar. */
    val activity: StateFlow<DayActivity?> = timeline.mapLatest { loaded ->
        loaded?.let { withContext(Dispatchers.Default) { DayActivity.of(it, { day -> Formats.dayStart(day) }, { time -> Formats.dayOf(time) }) } }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Everything on the chosen days, cleaned and ranked. */
    val range: StateFlow<RangeData?> = combine(timeline, _settings.map { it.days }.distinctUntilChanged(), _removed) { loaded, chosen, removed ->
        Triple(loaded, chosen, removed)
    }
        .mapLatest { (loaded, chosen, removed) ->
            if (loaded == null || chosen == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val spans = (0 until chosen.rangeCount).map { k ->
                        Formats.dayStart(chosen.start(k)) until Formats.dayStart(chosen.end(k) + 1)
                    }
                    RouteBuilder.build(loaded, spans, removed)
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Points that look like GPS errors, most suspicious first, for the route editor. */
    val suspects: StateFlow<IntArray> = range.mapLatest { data -> data?.let { withContext(Dispatchers.Default) { Suspects.find(it) } } ?: IntArray(0) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IntArray(0))

    /** The route with the chosen number of travel points. */
    val route: StateFlow<Route?> = combine(range, _settings.map { it.pointsFraction }.distinctUntilChanged()) { data, fraction -> data to fraction }
        .mapLatest { (data, fraction) ->
            if (data == null || data.size < 2) null else withContext(Dispatchers.Default) { data.select(PointBudget.count(fraction, data.size)) }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val look: StateFlow<Look> = _settings.map(::lookFor).distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, lookFor(_settings.value))

    val overlay: StateFlow<Overlay> = _settings.map { overlayFor(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Overlay.NONE)

    /** The preview: the same plan the export uses, at 30 frames a second. */
    val preview: StateFlow<Plan?> = combine(route, _settings.map(::MotionKey).distinctUntilChanged()) { route, key -> route to key }
        .mapLatest { (route, key) ->
            if (route == null) {
                null
            } else {
                withContext(Dispatchers.Default) {
                    val aspect = key.format.aspect
                    val inset = OverlayLayout.topInset(aspect, key.title, key.subtitle)
                    Planner.plan(route, key.motion(PREVIEW_FPS, aspect, inset))
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val summary: StateFlow<RangeSummary?> = combine(range, route, days, _settings) { data, route, bounds, settings ->
        val chosen = settings.days
        if (bounds == null || chosen == null) {
            null
        } else {
            RangeSummary(
                label = Formats.selection(app, chosen),
                preset = RangePreset.entries.firstOrNull { presetDays(it, bounds) == chosen },
                selection = chosen,
                firstDay = bounds.first,
                lastDay = bounds.second,
                distance = Formats.distance(data?.totalMeters ?: 0.0, settings.units),
                stops = data?.stops ?: 0,
                availablePoints = data?.size ?: 0,
                selectedPoints = route?.size ?: 0,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        tiles.apiKey = _settings.value.cartoKey
        viewModelScope.launch {
            val loaded = repository.load()
            if (loaded == null) _info.value = null
            timeline.value = loaded
            loaded?.let(::ensureRange)
            _loading.value = false
        }
        viewModelScope.launch(Dispatchers.IO) {
            tiles.trim()
            _cacheBytes.value = tiles.cacheBytes()
        }
    }

    fun update(change: (TraceSettings) -> TraceSettings) {
        val next = change(_settings.value)
        if (next == _settings.value) return
        if (next.cartoKey != _settings.value.cartoKey) _keyTest.value = KeyTest.Idle
        _settings.value = next
        tiles.apiKey = next.cartoKey
        store.save(next)
    }

    // region Timeline

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        importJob?.cancel()
        importJob = viewModelScope.launch {
            _import.value = ImportState.Reading(0f)
            try {
                val (loaded, info) = repository.import(uris) { progress -> _import.value = ImportState.Reading(progress) }
                timeline.value = loaded
                _info.value = info
                update { it.copy(days = null) }
                ensureRange(loaded)
                _import.value = ImportState.Idle
            } catch (cancelled: CancellationException) {
                _import.value = ImportState.Idle
                throw cancelled
            } catch (error: Throwable) {
                _import.value = ImportState.Failed(importMessage(error))
            }
        }
    }

    fun dismissImportError() {
        _import.value = ImportState.Idle
    }

    fun removeTimeline() {
        repository.clear()
        timeline.value = null
        _info.value = null
        _removed.value = Exclusions.NONE
        undo.clear()
        _canUndo.value = false
        update { it.copy(days = null) }
    }

    private fun importMessage(error: Throwable): String = app.getString(
        when (error) {
            is TimelineFormatException -> when (error.reason) {
                TimelineFormatException.Reason.NOT_A_TIMELINE -> R.string.error_not_timeline
                TimelineFormatException.Reason.NO_LOCATIONS -> R.string.error_no_locations
                TimelineFormatException.Reason.EMPTY_ARCHIVE -> R.string.error_empty_archive
            }
            is MalformedJsonException, is IllegalStateException, is NumberFormatException -> R.string.error_not_timeline
            is OutOfMemoryError -> R.string.error_too_large
            else -> R.string.error_unreadable
        },
    )

    /** Picks the latest week if nothing valid is chosen yet. */
    private fun ensureRange(loaded: Timeline) {
        val bounds = Formats.dayOf(loaded.times.first()) to Formats.dayOf(loaded.times.last())
        val chosen = _settings.value.days
        val valid = chosen != null && chosen.last >= bounds.first && chosen.first <= bounds.second
        if (!valid) update { it.copy(days = presetDays(RangePreset.WEEK, bounds)) }
    }

    // endregion

    // region Dates

    fun preset(preset: RangePreset) {
        val bounds = days.value ?: return
        update { it.copy(days = presetDays(preset, bounds)) }
    }

    /** Moves the chosen days back or forward by their own span. */
    fun shiftRange(direction: Int) {
        val chosen = _settings.value.days ?: return
        val span = chosen.last - chosen.first + 1
        update { it.copy(days = chosen.shifted(direction * span)) }
    }

    fun setDays(selection: DaySelection) {
        update { it.copy(days = selection) }
    }

    private fun presetDays(preset: RangePreset, bounds: Pair<Long, Long>): DaySelection {
        val last = bounds.second
        val start = when (preset) {
            RangePreset.DAY -> last
            RangePreset.WEEK -> last - 6
            RangePreset.MONTH -> last - 29
            RangePreset.YEAR -> last - 364
            RangePreset.ALL -> bounds.first
        }
        return DaySelection.range(start, last)
    }

    // endregion

    // region Removing GPS errors

    /**
     * Removes points [from]..[to] of [data], the range the person picked them in, for good. A
     * single point at a stop takes the whole stop with it, arriving and leaving.
     */
    fun removePoints(data: RangeData, from: Int, to: Int) {
        if (data.size == 0) return
        var first = minOf(from, to).coerceIn(0, data.size - 1)
        var last = maxOf(from, to).coerceIn(0, data.size - 1)
        if (first == last) {
            fun same(a: Int, b: Int) = data.x[a] == data.x[b] && data.y[a] == data.y[b]
            while (first > 0 && !data.breakBefore[first] && same(first - 1, first)) first--
            while (last + 1 < data.size && !data.breakBefore[last + 1] && same(last, last + 1)) last++
        }
        changeRemoved(_removed.value.plus(data.times[first], data.times[last]))
    }

    fun undoRemoval() {
        val previous = undo.removeLastOrNull() ?: return
        _canUndo.value = undo.isNotEmpty()
        _removed.value = previous
        viewModelScope.launch { repository.saveRemoved(previous) }
    }

    fun restorePoints() {
        if (_removed.value.isEmpty) return
        changeRemoved(Exclusions.NONE)
    }

    private fun changeRemoved(next: Exclusions) {
        if (next == _removed.value) return
        undo.addLast(_removed.value)
        while (undo.size > MAX_UNDO) undo.removeAt(0)
        _canUndo.value = true
        _removed.value = next
        viewModelScope.launch { repository.saveRemoved(next) }
    }

    // endregion

    // region Export

    fun exportVideo() = startExport(image = false)

    fun saveImage() = startExport(image = true)

    fun cancelExport() {
        exportJob?.cancel()
    }

    fun dismissExport() {
        if (_export.value !is ExportState.Running) _export.value = ExportState.Idle
    }

    /** The last saved video or image, for sharing or opening. */
    fun exportedUri(): Uri? = exported

    private fun startExport(image: Boolean) {
        val route = route.value ?: return
        if (_export.value is ExportState.Running) return
        val s = _settings.value
        exportJob = viewModelScope.launch {
            _export.value = ExportState.Running(image, ExportState.Stage.PREPARING, 0f)
            try {
                val (width, height) = withContext(Dispatchers.Default) {
                    if (image) s.format.size(maxOf(1080, s.quality.shortSide)) else s.format.size(s.quality.shortSide).let { (w, h) -> VideoSizes.fit(w, h, s.fps) }
                }
                val overlay = overlayFor(s)
                val aspect = width.toDouble() / height
                val inset = OverlayLayout.topInset(aspect, overlay.title != null, overlay.hasSubtitle)
                val plan = withContext(Dispatchers.Default) { Planner.plan(route, MotionKey(s).motion(s.fps, aspect, inset)) }
                val name = "Trace " + SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(Date())
                val onProgress = { progress: VideoExporter.Progress ->
                    _export.value = when (progress) {
                        is VideoExporter.Progress.Map -> ExportState.Running(image, ExportState.Stage.MAP, progress.done.toFloat() / progress.total.coerceAtLeast(1))
                        is VideoExporter.Progress.Frames -> ExportState.Running(
                            image,
                            if (progress.done == progress.total) ExportState.Stage.SAVING else ExportState.Stage.FRAMES,
                            progress.done.toFloat() / progress.total,
                        )
                    }
                }
                val look = lookFor(s)
                exported = if (image) {
                    exporter.image(plan, width, height, look, overlay, name, onProgress)
                } else {
                    exporter.video(plan, width, height, look, overlay, name, onProgress)
                }
                _export.value = ExportState.Done(image)
            } catch (cancelled: CancellationException) {
                _export.value = ExportState.Idle
                throw cancelled
            } catch (error: Throwable) {
                val detail = if (error is IOException) error.message else null
                _export.value = ExportState.Failed(detail ?: app.getString(R.string.error_export))
            } finally {
                launch(Dispatchers.IO) {
                    tiles.trim()
                    _cacheBytes.value = tiles.cacheBytes()
                }
            }
        }
    }

    // endregion

    // region Map key and cache

    fun testKey() {
        viewModelScope.launch {
            _keyTest.value = KeyTest.Testing
            val style = _settings.value.style
            val set = if (style.usesCarto) style.tileSet(true) else "light_all"
            val tile = tiles.sample(set)
            _keyTest.value = if (tile != null) KeyTest.Loaded(tile.asImageBitmap()) else KeyTest.Failed
        }
    }

    fun clearCache() {
        viewModelScope.launch(Dispatchers.IO) {
            tiles.clear()
            _cacheBytes.value = tiles.cacheBytes()
        }
    }

    // endregion

    private fun lookFor(s: TraceSettings) = Look(s.style, s.labels, s.routeColor, s.lineWidth.factor, s.showPoints)

    private fun overlayFor(s: TraceSettings): Overlay {
        val chosen = s.days ?: return Overlay.NONE
        val label = Formats.selection(app, chosen)
        val title = if (s.showTitle) s.title.trim().ifEmpty { label } else null
        val units = s.units
        return Overlay(title, s.showDate, s.showDistance, label, Formats.DateLabel(app, chosen)) { meters -> Formats.distance(meters, units) }
    }

    /** The settings that change the camera and timing. */
    private data class MotionKey(
        val seconds: Int,
        val format: VideoFormat,
        val camera: CameraMode,
        val distance: CameraDistance,
        val lag: Float,
        val smoothness: Float,
        val pause: Boolean,
        val title: Boolean,
        val subtitle: Boolean,
    ) {
        constructor(s: TraceSettings) : this(
            s.durationSeconds, s.format, s.camera, s.cameraDistance, s.cameraLag, s.smoothness, s.pauseAtStops,
            s.showTitle, s.showDate || s.showDistance,
        )

        fun motion(fps: Int, aspect: Double, inset: Double) =
            MotionSettings(seconds.toDouble(), fps, aspect, smoothness.toDouble(), inset, camera, pause, distance, lag.toDouble())
    }

    private companion object {
        const val PREVIEW_FPS = 30
        const val MAX_UNDO = 50
    }
}
