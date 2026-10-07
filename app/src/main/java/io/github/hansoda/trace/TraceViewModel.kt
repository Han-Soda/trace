package io.github.hansoda.trace

import android.app.Application
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.stream.MalformedJsonException
import io.github.hansoda.trace.data.Inbox
import io.github.hansoda.trace.data.Timeline
import io.github.hansoda.trace.data.TimelineFormatException
import io.github.hansoda.trace.data.TimelineInfo
import io.github.hansoda.trace.data.TimelineRepository
import io.github.hansoda.trace.export.VideoExporter
import io.github.hansoda.trace.export.VideoSizes
import io.github.hansoda.trace.media.MediaFinder
import io.github.hansoda.trace.media.MediaItem
import io.github.hansoda.trace.media.MediaLibrary
import io.github.hansoda.trace.media.PhotoStore
import io.github.hansoda.trace.media.Places
import io.github.hansoda.trace.motion.CameraDistance
import io.github.hansoda.trace.motion.CameraMode
import io.github.hansoda.trace.motion.Moment
import io.github.hansoda.trace.motion.MotionSettings
import io.github.hansoda.trace.motion.Plan
import io.github.hansoda.trace.motion.Planner
import io.github.hansoda.trace.motion.Speed
import io.github.hansoda.trace.render.Caption
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
import io.github.hansoda.trace.settings.AppLanguage
import io.github.hansoda.trace.settings.SettingsStore
import io.github.hansoda.trace.settings.TraceSettings
import io.github.hansoda.trace.settings.VideoFormat
import io.github.hansoda.trace.tiles.TileStore
import io.github.hansoda.trace.ui.ClipTrim
import io.github.hansoda.trace.ui.ExportState
import io.github.hansoda.trace.ui.FoundMedia
import io.github.hansoda.trace.ui.Formats
import io.github.hansoda.trace.ui.ImportState
import io.github.hansoda.trace.ui.KeyTest
import io.github.hansoda.trace.ui.MediaAdding
import io.github.hansoda.trace.ui.MediaFinding
import io.github.hansoda.trace.ui.PhotoCard
import io.github.hansoda.trace.ui.PhotosSummary
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalCoroutinesApi::class)
class TraceViewModel(application: Application) : AndroidViewModel(application) {
    private val app: Application get() = getApplication()

    /** Bumped when the language changes, so text made here is made again. */
    private val language = MutableStateFlow(0)
    private var localized: Pair<AppLanguage, Context>? = null

    /** The app in Trace's own language, which can differ from the phone's, for text made here. */
    private val text: Context
        get() {
            val chosen = AppLanguage.current(app)
            localized?.let { (cached, context) -> if (cached == chosen) return context }
            return AppLanguage.apply(app).also { localized = chosen to it }
        }
    private val store = SettingsStore(application)
    private val repository = TimelineRepository(application)
    val tiles = TileStore(application)
    private val library = MediaLibrary(application)
    val photos = PhotoStore(library)
    private val places = Places(application)
    private val finder = MediaFinder(application)
    private val exporter = VideoExporter(application, tiles, photos.now)

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
    val cacheSize: StateFlow<String> = combine(_cacheBytes, language) { bytes, _ -> Formatter.formatShortFileSize(text, bytes) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Fixes removed by hand, and earlier versions for undo. */
    private val _removed = MutableStateFlow(repository.removed())
    val removed: StateFlow<Exclusions> = _removed.asStateFlow()
    private val undo = ArrayDeque<Exclusions>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    val removedPoints: StateFlow<Int> = combine(timeline, _removed) { loaded, removed -> loaded?.let { removed.countIn(it) } ?: 0 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** Photos and clips added to Trace, in the order they were taken. */
    private val _media = MutableStateFlow(library.load())
    private val _mediaAdding = MutableStateFlow<MediaAdding?>(null)
    val mediaAdding: StateFlow<MediaAdding?> = _mediaAdding.asStateFlow()
    private var mediaJob: Job? = null
    private val _finding = MutableStateFlow<MediaFinding?>(null)
    val finding: StateFlow<MediaFinding?> = _finding.asStateFlow()
    private val _trimming = MutableStateFlow<ClipTrim?>(null)
    val trimming: StateFlow<ClipTrim?> = _trimming.asStateFlow()

    /** Photos whose place was looked up this time round, so failures aren't asked about again and again. */
    private val placesAsked = HashSet<String>()

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

    val overlay: StateFlow<Overlay> = combine(_settings, language, _media, timeline) { settings, _, media, loaded -> overlayFor(settings, media, loaded) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Overlay.NONE)

    /** The photos and clips taken on the chosen days, with their thumbnails. */
    val photosOnDays: StateFlow<PhotosSummary> = combine(range, _media) { data, media -> data to media }
        .mapLatest { (data, media) ->
            withContext(Dispatchers.IO) {
                val onDays = media.filter { onDays(it, data) }
                PhotosSummary(
                    onDays.map {
                        PhotoCard(
                            it.id, it.isClip, it.seconds, photos.thumbnailNow(it.id)?.asImageBitmap(),
                            it.source.takeIf { _ -> it.canTrim }, it.sourceMs, it.startMs, it.audio,
                        )
                    },
                    media.size - onDays.size,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, PhotosSummary.EMPTY)

    /** The preview: the same plan the export uses, at 30 frames a second. */
    val preview: StateFlow<Plan?> = combine(route, combine(_settings, _media, ::MotionKey).distinctUntilChanged()) { route, key -> route to key }
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

    val summary: StateFlow<RangeSummary?> = combine(range, route, days, _settings, language) { data, route, bounds, settings, _ ->
        val chosen = settings.days
        if (bounds == null || chosen == null) {
            null
        } else {
            RangeSummary(
                label = Formats.selection(text, chosen),
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
        // Names for the places photos were taken, once the history is there to say where.
        viewModelScope.launch {
            combine(_media.map { list -> list.filter(::needsPlace).map { it.id } }.distinctUntilChanged(), timeline.map { it != null }, language) { _, _, _ -> }
                .collectLatest { namePlaces() }
        }
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

    /** Makes text, and map labels, again in the newly chosen language. */
    fun languageChanged() {
        tiles.forgetDrawn()
        placesAsked.clear()
        language.value++
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

    fun import(uris: List<Uri>, afterwards: () -> Unit = {}) {
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
            } finally {
                afterwards()
            }
        }
    }

    /** Imports a Timeline export that was saved straight into Trace while it was away. */
    fun importInbox() {
        if (importJob?.isActive == true) return
        val waiting = Inbox.waiting(app)
        if (waiting.isEmpty()) return
        // The newest export holds everything the older ones do.
        import(listOf(Uri.fromFile(waiting.first()))) { Inbox.remove(waiting) }
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

    private fun importMessage(error: Throwable): String = text.getString(
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

    // region Photos and clips

    fun addMedia(uris: List<Uri>) {
        if (uris.isEmpty() || mediaJob?.isActive == true) return
        mediaJob = viewModelScope.launch {
            _mediaAdding.value = MediaAdding.Working(0, uris.size)
            try {
                val added = library.add(uris, _media.value) { done, total -> _mediaAdding.value = MediaAdding.Working(done, total) }
                val all = (_media.value + added.items).sortedBy { it.time }
                withContext(Dispatchers.IO) { library.save(all) }
                _media.value = all
                val elsewhere = added.items.count { !onDays(it, range.value) }
                _mediaAdding.value = MediaAdding.Finished(added.items.size, added.undated, added.unreadable, elsewhere)
            } catch (cancelled: CancellationException) {
                _mediaAdding.value = null
                throw cancelled
            } catch (_: Exception) {
                _mediaAdding.value = MediaAdding.Finished(0, 0, uris.size, 0)
            }
        }
    }

    fun removeMedia(id: String) {
        val item = _media.value.firstOrNull { it.id == id } ?: return
        val remaining = _media.value - item
        _media.value = remaining
        photos.forget(id)
        viewModelScope.launch(Dispatchers.IO) {
            library.remove(item)
            library.save(remaining)
        }
    }

    /** Keeps another part of a clip's video: [lengthMs] from [startMs]. */
    fun trimClip(id: String, startMs: Long, lengthMs: Long) {
        val item = _media.value.firstOrNull { it.id == id } ?: return
        if (mediaJob?.isActive == true) return
        mediaJob = viewModelScope.launch {
            _trimming.value = ClipTrim.Working(id, 0f)
            try {
                val trimmed = library.trim(item, startMs, lengthMs) { progress -> _trimming.value = ClipTrim.Working(id, progress) }
                photos.forget(id)
                val all = _media.value.map { if (it.id == id) trimmed else it }
                withContext(Dispatchers.IO) { library.save(all) }
                _media.value = all
                _trimming.value = null
            } catch (cancelled: CancellationException) {
                _trimming.value = null
                throw cancelled
            } catch (_: Exception) {
                _trimming.value = ClipTrim.Failed(id)
            }
        }
    }

    fun dismissTrimError() {
        if (_trimming.value is ClipTrim.Failed) _trimming.value = null
    }

    /** Looks through the gallery for photos and videos taken on the chosen days. */
    fun findMedia() {
        val chosen = _settings.value.days ?: return
        if (_finding.value == MediaFinding.Searching) return
        viewModelScope.launch {
            _finding.value = MediaFinding.Searching
            val spans = (0 until chosen.rangeCount).map { k -> Formats.dayStart(chosen.start(k)) until Formats.dayStart(chosen.end(k) + 1) }
            val kept = _media.value.mapTo(HashSet()) { it.time }
            val found = withContext(Dispatchers.IO) { finder.find(spans) }.filter { it.time !in kept }
            val flags = DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_ALL
            val items = found.map { FoundMedia(it.uri, it.time, it.video, it.durationMs, DateUtils.formatDateTime(text, it.time, flags)) }
            _finding.value = MediaFinding.Found(items, suggest(items))
        }
    }

    /**
     * An even spread through the trip of about as many as the video has time for, so the
     * first choice is a good one.
     */
    private fun suggest(items: List<FoundMedia>): Set<Uri> {
        val s = _settings.value
        val room = (s.durationSeconds * 0.4 / maxOf(1.5, s.photoSeconds.toDouble())).toInt().coerceIn(3, 24)
        if (items.size <= room) return items.mapTo(HashSet()) { it.uri }
        return List(room) { k -> items[(2 * k + 1) * items.size / (2 * room)].uri }.toSet()
    }

    private val foundThumbnails = LruCache<Uri, ImageBitmap>(FOUND_THUMBNAILS)

    suspend fun foundThumbnail(item: FoundMedia): ImageBitmap? = foundThumbnails.get(item.uri) ?: withContext(Dispatchers.IO) {
        finder.thumbnail(MediaFinder.Found(item.uri, item.time, item.video, item.durationMs), FOUND_THUMBNAIL)?.asImageBitmap()
            ?.also { foundThumbnails.put(item.uri, it) }
    }

    fun addFound(uris: List<Uri>) {
        _finding.value = null
        addMedia(uris)
    }

    fun dismissFinding() {
        _finding.value = null
    }

    fun galleryDenied() {
        _finding.value = MediaFinding.Denied
    }

    private fun needsPlace(item: MediaItem): Boolean = item.place == null || item.placeLanguage != AppLanguage.current(app).tag

    /** Names the places of photos that have none yet, in Trace's language, all at once at the end. */
    private suspend fun namePlaces() {
        val loaded = timeline.value ?: return
        if (!places.available) return
        val language = AppLanguage.current(app)
        val locale = AppLanguage.locale(app)
        val named = HashMap<String, String>()
        for (item in _media.value.filter(::needsPlace)) {
            if (!placesAsked.add(item.id + "/" + item.shownTime + "/" + language.tag)) continue
            val (latitude, longitude) = Places.locate(loaded, item.shownTime) ?: continue
            places.name(latitude, longitude, locale)?.let { named[item.id] = it }
        }
        if (named.isEmpty()) return
        _media.update { list -> list.map { item -> named[item.id]?.let { item.copy(place = it, placeLanguage = language.tag) } ?: item } }
        val all = _media.value
        withContext(Dispatchers.IO) { library.save(all) }
    }

    fun dismissMediaNote() {
        if (_mediaAdding.value is MediaAdding.Finished) _mediaAdding.value = null
    }

    /** Whether [item] was taken during the chosen days, give or take the half hour the planner allows. */
    private fun onDays(item: MediaItem, data: RangeData?): Boolean {
        if (data == null || data.size == 0) return false
        return item.shownTime in data.times[0] - MOMENT_SLACK_MS..data.times[data.size - 1] + MOMENT_SLACK_MS
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
                val plan = withContext(Dispatchers.Default) { Planner.plan(route, MotionKey(s, _media.value).motion(s.fps, aspect, inset)) }
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
                    exporter.image(plan, width, height, look, overlay, name, onProgress = onProgress)
                } else {
                    exporter.video(plan, width, height, look, overlay, name, if (s.clipSound) photos.sounds else null, onProgress)
                }
                _export.value = ExportState.Done(image)
            } catch (cancelled: CancellationException) {
                _export.value = ExportState.Idle
                throw cancelled
            } catch (error: Throwable) {
                val detail = if (error is IOException) error.message else null
                _export.value = ExportState.Failed(detail ?: text.getString(R.string.error_export))
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

    private fun lookFor(s: TraceSettings) =
        Look(s.style, s.labels, s.routeColor, s.lineWidth.factor, s.showPoints, s.photoStyle, s.photoCorner)

    private fun overlayFor(s: TraceSettings, media: List<MediaItem> = _media.value, loaded: Timeline? = timeline.value): Overlay {
        val chosen = s.days ?: return Overlay.NONE
        val label = Formats.selection(text, chosen)
        val title = if (s.showTitle) s.title.trim().ifEmpty { label } else null
        val units = s.units
        return Overlay(
            title, s.showDate, s.showDistance, label, Formats.DateLabel(text, chosen), { meters -> Formats.distance(meters, units) },
            if (s.captions) captionsFor(media, loaded) else emptyMap(),
        )
    }

    /** Where and when each photo was taken, in the time zone the trip was in. */
    private fun captionsFor(media: List<MediaItem>, loaded: Timeline?): Map<String, Caption> {
        val time = Formats.PhotoTime(text)
        return media.associate { item -> item.id to Caption(item.place, time(item.shownTime, Places.offset(loaded, item.shownTime))) }
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
        val moments: List<Moment>,
        val speed: Speed,
        val intro: Boolean,
    ) {
        constructor(s: TraceSettings, media: List<MediaItem>) : this(
            s.durationSeconds, s.format, s.camera, s.cameraDistance, s.cameraLag, s.smoothness, s.pauseAtStops,
            s.showTitle, s.showDate || s.showDistance,
            // A clip plays its length, plus time to come up and to go.
            media.map { Moment(it.id, it.shownTime, if (it.isClip) it.seconds + 0.8 else s.photoSeconds.toDouble(), it.frames, it.fps) },
            s.speed, s.intro,
        )

        fun motion(fps: Int, aspect: Double, inset: Double) = MotionSettings(
            seconds.toDouble(), fps, aspect, smoothness.toDouble(), inset, camera, pause, distance, lag.toDouble(), moments, speed, intro,
        )
    }

    private companion object {
        const val MOMENT_SLACK_MS = 30 * 60_000L
        const val FOUND_THUMBNAIL = 256
        const val FOUND_THUMBNAILS = 300
        const val PREVIEW_FPS = 30
        const val MAX_UNDO = 50
    }
}
