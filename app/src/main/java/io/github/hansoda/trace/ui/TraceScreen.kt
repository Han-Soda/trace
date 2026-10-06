package io.github.hansoda.trace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import io.github.hansoda.trace.R
import io.github.hansoda.trace.render.MapStyle
import io.github.hansoda.trace.route.PointBudget
import io.github.hansoda.trace.settings.LineWidth
import io.github.hansoda.trace.settings.Palette
import io.github.hansoda.trace.settings.TraceSettings
import io.github.hansoda.trace.settings.VideoFormat
import java.text.NumberFormat
import kotlin.math.roundToInt

/**
 * The whole app: a live preview on top, the few choices that matter below, and one button to
 * export. [preview] draws the video frame at the given second.
 */
@Composable
fun TraceScreen(state: ScreenState, actions: ScreenActions, preview: @Composable (Modifier, Float) -> Unit) {
    var showSettings by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(showSettings = state.hasTimeline, onSettings = { showSettings = true })
            Box(Modifier.weight(1f)) {
                when {
                    state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    !state.hasTimeline -> Welcome(state.import, actions)
                    else -> Editor(state, actions, preview)
                }
            }
            if (state.hasTimeline && !state.loading) ExportBar(state, actions)
        }
    }

    if (showSettings) SettingsSheet(state, actions) { showSettings = false }
    ImportDialogs(state, actions)
    ExportDialogs(state.export, actions)
}

@Composable
private fun TopBar(showSettings: Boolean, onSettings: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 20.dp, end = 8.dp, top = 8.dp, bottom = 4.dp)
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (showSettings) {
            IconButton(onClick = onSettings) { Icon(TraceIcons.Settings, contentDescription = stringResource(R.string.settings)) }
        }
    }
}

// region Welcome

@Composable
private fun Welcome(import: ImportState, actions: ScreenActions) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .navigationBarsPadding()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(TraceIcons.Mark, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(R.string.welcome_tagline),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        if (import is ImportState.Reading) {
            LinearProgressIndicator(progress = { import.progress }, modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth())
            Text(
                stringResource(R.string.reading_file, (import.progress * 100).roundToInt()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            Button(onClick = actions.openFiles, modifier = Modifier.height(52.dp)) {
                Text(stringResource(R.string.open_timeline), modifier = Modifier.padding(horizontal = 12.dp))
            }
        }
        Spacer(Modifier.height(40.dp))
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Column(Modifier.padding(20.dp)) {
                Text(stringResource(R.string.how_to_export_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(R.string.how_to_export_steps),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

// endregion

// region Editor

@Composable
private fun Editor(state: ScreenState, actions: ScreenActions, preview: @Composable (Modifier, Float) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = maxWidth
        val height = maxHeight
        if (width >= 720.dp) {
            Row(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f).fillMaxHeight().padding(20.dp), contentAlignment = Alignment.Center) {
                    PreviewPane(state, preview, maxWidth = width - 440.dp, maxHeight = height - 120.dp)
                }
                Column(
                    Modifier
                        .width(400.dp)
                        .fillMaxHeight()
                        .imePadding()
                        .verticalScroll(rememberScrollState())
                        .padding(end = 20.dp, bottom = 24.dp),
                ) { Controls(state, actions) }
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                PreviewPane(state, preview, maxWidth = width - 40.dp, maxHeight = height * 0.56f)
                Controls(state, actions)
            }
        }
    }
}

@Composable
private fun PreviewPane(state: ScreenState, preview: @Composable (Modifier, Float) -> Unit, maxWidth: Dp, maxHeight: Dp) {
    val seconds = state.settings.durationSeconds.toFloat()
    var playing by rememberSaveable { mutableStateOf(true) }
    var time by rememberSaveable { mutableFloatStateOf(0f) }
    LaunchedEffect(playing, state.previewReady, seconds) {
        if (!playing || !state.previewReady) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            time = (time + (now - last) / 1e9f).let { if (it >= seconds) 0f else it }
            last = now
        }
    }
    val aspect = state.settings.format.aspect.toFloat()
    val width = min(maxWidth, maxHeight * aspect)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .padding(top = 8.dp)
                .size(width, width / aspect)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) {
            when {
                state.previewReady -> preview(Modifier.fillMaxSize(), time.coerceAtMost(seconds))
                state.range?.empty == true -> Text(
                    stringResource(R.string.no_movement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                else -> CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
            }
        }
        Row(Modifier.width(maxOf(width, 280.dp)).padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { playing = !playing }, enabled = state.previewReady) {
                Icon(
                    if (playing) TraceIcons.Pause else TraceIcons.Play,
                    contentDescription = stringResource(if (playing) R.string.pause else R.string.play),
                )
            }
            Slider(
                value = (time / seconds).coerceIn(0f, 1f),
                onValueChange = {
                    playing = false
                    time = it * seconds
                },
                enabled = state.previewReady,
                modifier = Modifier.weight(1f),
            )
            Text(
                clockText(time),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp),
            )
        }
    }
}

@Composable
private fun Controls(state: ScreenState, actions: ScreenActions) {
    val s = state.settings
    Column(Modifier.fillMaxWidth()) {
        state.range?.let { Dates(it, actions) }
        Motion(state, actions)
        Style(s, actions)
        TextOptions(state, actions)
        VideoOptions(s, actions)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Dates(range: RangeSummary, actions: ScreenActions) {
    var picking by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.dates))
    Row(verticalAlignment = Alignment.CenterVertically) {
        FilledTonalIconButton(onClick = { actions.shiftRange(-1) }, enabled = range.canGoBack) {
            Icon(TraceIcons.Back, contentDescription = stringResource(R.string.earlier))
        }
        Text(
            range.label,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClickLabel = stringResource(R.string.choose_dates)) { picking = true }
                .padding(vertical = 10.dp, horizontal = 8.dp),
        )
        FilledTonalIconButton(onClick = { actions.shiftRange(1) }, enabled = range.canGoForward) {
            Icon(TraceIcons.Forward, contentDescription = stringResource(R.string.later))
        }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 6.dp), horizontalArrangement = RowGap) {
        val names = mapOf(
            RangePreset.DAY to R.string.preset_day,
            RangePreset.WEEK to R.string.preset_week,
            RangePreset.MONTH to R.string.preset_month,
            RangePreset.YEAR to R.string.preset_year,
            RangePreset.ALL to R.string.preset_all,
        )
        for ((preset, name) in names) {
            FilterChip(selected = range.preset == preset, onClick = { actions.preset(preset) }, label = { Text(stringResource(name)) })
        }
    }
    Text(
        listOf(
            range.distance,
            pluralStringResource(R.plurals.days, range.days, range.days),
            pluralStringResource(R.plurals.stops, range.stops, range.stops),
        ).joinToString("  ·  "),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp),
    )

    if (picking) {
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = range.startDay * DAY_MS,
            initialSelectedEndDateMillis = range.endDay * DAY_MS,
            // The picker rejects selections outside its years, and shifted ranges can leave the data.
            yearRange = yearOf(minOf(range.firstDay, range.startDay))..yearOf(maxOf(range.lastDay, range.endDay)),
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis
                        if (start != null) actions.setRange(start / DAY_MS, (state.selectedEndDateMillis ?: start) / DAY_MS)
                        picking = false
                    },
                ) { Text(stringResource(R.string.apply)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.weight(1f), showModeToggle = false)
        }
    }
}

@Composable
private fun Motion(state: ScreenState, actions: ScreenActions) {
    val s = state.settings
    SectionTitle(stringResource(R.string.motion))
    LabeledSlider(
        label = stringResource(R.string.zoom_smoothness),
        value = "${(s.smoothness * 100).roundToInt()}%",
        position = s.smoothness,
        onChange = { value -> actions.update { it.copy(smoothness = (value * 20).roundToInt() / 20f) } },
        hint = stringResource(R.string.zoom_smoothness_hint),
    )
    val range = state.range
    val available = range?.availablePoints ?: 0
    val count = if (available > 0) PointBudget.count(s.pointsFraction, available) else 0
    val numbers = NumberFormat.getIntegerInstance()
    LabeledSlider(
        label = stringResource(R.string.travel_points),
        value = if (available > 0) stringResource(R.string.travel_points_value, numbers.format(count), numbers.format(available)) else "–",
        position = s.pointsFraction,
        onChange = { value -> actions.update { it.copy(pointsFraction = value) } },
        hint = stringResource(R.string.travel_points_hint),
    )
}

@Composable
private fun Style(s: TraceSettings, actions: ScreenActions) {
    var pickingColor by remember { mutableStateOf(false) }
    SectionTitle(stringResource(R.string.style))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = RowGap) {
        val names = mapOf(
            MapStyle.LIGHT to R.string.style_light,
            MapStyle.DARK to R.string.style_dark,
            MapStyle.VOYAGER to R.string.style_voyager,
            MapStyle.PAPER to R.string.style_paper,
            MapStyle.INK to R.string.style_ink,
        )
        for ((style, name) in names) {
            FilterChip(
                selected = s.style == style,
                onClick = { actions.update { it.copy(style = style) } },
                label = { Text(stringResource(name)) },
                leadingIcon = {
                    Box(
                        Modifier
                            .size(14.dp)
                            .clip(CircleShape)
                            .background(Color(style.sea))
                            .padding(3.dp)
                            .clip(CircleShape)
                            .background(Color(style.landColor)),
                    )
                },
            )
        }
    }
    if (s.style.usesCarto && s.cartoKey.isBlank()) {
        Text(
            stringResource(R.string.needs_key_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
    if (s.style.usesCarto) {
        SwitchRow(stringResource(R.string.labels), s.labels, { on -> actions.update { it.copy(labels = on) } })
    }

    Text(stringResource(R.string.color), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        val colors = Palette.colors + listOfNotNull(s.customColor?.takeIf { it !in Palette.colors })
        for (color in colors) {
            Swatch(Color(color), selected = s.routeColor == color, onClick = { actions.update { it.copy(routeColor = color) } })
        }
        PickerSwatch(onClick = { pickingColor = true }, label = stringResource(R.string.pick_color))
    }

    Text(stringResource(R.string.line), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    val lineNames = mapOf(
        LineWidth.THIN to stringResource(R.string.line_thin),
        LineWidth.REGULAR to stringResource(R.string.line_regular),
        LineWidth.BOLD to stringResource(R.string.line_bold),
    )
    Choice(LineWidth.entries, s.lineWidth, { lineNames.getValue(it) }, { width -> actions.update { it.copy(lineWidth = width) } })
    SwitchRow(stringResource(R.string.mark_points), s.showPoints, { on -> actions.update { it.copy(showPoints = on) } }, Modifier.padding(top = 4.dp))

    if (pickingColor) {
        ColorPickerDialog(
            initial = s.customColor ?: s.routeColor,
            onDismiss = { pickingColor = false },
            onPick = { color ->
                actions.update { it.copy(routeColor = color, customColor = color) }
                pickingColor = false
            },
        )
    }
}

@Composable
private fun TextOptions(state: ScreenState, actions: ScreenActions) {
    val s = state.settings
    SectionTitle(stringResource(R.string.text))
    SwitchRow(stringResource(R.string.title), s.showTitle, { on -> actions.update { it.copy(showTitle = on) } })
    if (s.showTitle) {
        OutlinedTextField(
            value = s.title,
            onValueChange = { text -> actions.update { it.copy(title = text.take(60)) } },
            placeholder = { Text(state.range?.label.orEmpty()) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
        )
    }
    SwitchRow(stringResource(R.string.date), s.showDate, { on -> actions.update { it.copy(showDate = on) } })
    SwitchRow(stringResource(R.string.distance), s.showDistance, { on -> actions.update { it.copy(showDistance = on) } })
}

@Composable
private fun VideoOptions(s: TraceSettings, actions: ScreenActions) {
    SectionTitle(stringResource(R.string.video))
    Choice(VideoFormat.entries, s.format, { it.id }, { format -> actions.update { it.copy(format = format) } })
    LabeledSlider(
        label = stringResource(R.string.length),
        value = stringResource(R.string.seconds_value, s.durationSeconds),
        position = s.durationSeconds.toFloat(),
        onChange = { value -> actions.update { it.copy(durationSeconds = value.roundToInt()) } },
        range = TraceSettings.MIN_SECONDS.toFloat()..TraceSettings.MAX_SECONDS.toFloat(),
        modifier = Modifier.padding(top = 8.dp),
    )
}

// endregion

// region Export

@Composable
private fun ExportBar(state: ScreenState, actions: ScreenActions) {
    val export = state.export
    val ready = state.previewReady
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (export is ExportState.Running) {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        val percent = (export.progress * 100).roundToInt()
                        Text(
                            when (export.stage) {
                                ExportState.Stage.PREPARING -> stringResource(R.string.preparing)
                                ExportState.Stage.MAP -> stringResource(R.string.downloading_map, percent)
                                ExportState.Stage.FRAMES -> stringResource(R.string.rendering, percent)
                                ExportState.Stage.SAVING -> stringResource(R.string.saving)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        val bar = Modifier.fillMaxWidth().padding(top = 8.dp)
                        if (export.stage == ExportState.Stage.PREPARING || export.stage == ExportState.Stage.SAVING) {
                            LinearProgressIndicator(bar)
                        } else {
                            LinearProgressIndicator(progress = { export.progress }, modifier = bar)
                        }
                    }
                    TextButton(onClick = actions.cancelExport, modifier = Modifier.padding(start = 8.dp)) { Text(stringResource(R.string.cancel)) }
                }
            } else {
                Row(
                    Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Button(onClick = actions.exportVideo, enabled = ready, modifier = Modifier.weight(1f).height(52.dp)) {
                        Text(stringResource(R.string.export_video), style = MaterialTheme.typography.titleMedium)
                    }
                    FilledTonalIconButton(onClick = actions.saveImage, enabled = ready, modifier = Modifier.size(52.dp)) {
                        Icon(TraceIcons.Image, contentDescription = stringResource(R.string.save_image))
                    }
                }
            }
        }
    }
}

@Composable
private fun ExportDialogs(export: ExportState, actions: ScreenActions) {
    when (export) {
        is ExportState.Done -> AlertDialog(
            onDismissRequest = actions.dismissExport,
            title = { Text(stringResource(if (export.image) R.string.image_saved else R.string.video_saved)) },
            text = { Text(stringResource(if (export.image) R.string.saved_to_pictures else R.string.saved_to_movies)) },
            confirmButton = {
                Row {
                    TextButton(onClick = actions.openExport) { Text(stringResource(R.string.open)) }
                    TextButton(onClick = actions.shareExport) { Text(stringResource(R.string.share)) }
                }
            },
            dismissButton = { TextButton(onClick = actions.dismissExport) { Text(stringResource(R.string.close)) } },
        )
        is ExportState.Failed -> AlertDialog(
            onDismissRequest = actions.dismissExport,
            title = { Text(stringResource(R.string.export_failed)) },
            text = { Text(export.message) },
            confirmButton = { TextButton(onClick = actions.dismissExport) { Text(stringResource(R.string.ok)) } },
        )
        else -> Unit
    }
}

@Composable
private fun ImportDialogs(state: ScreenState, actions: ScreenActions) {
    when (val import = state.import) {
        is ImportState.Failed -> AlertDialog(
            onDismissRequest = actions.dismissImportError,
            title = { Text(stringResource(R.string.import_failed)) },
            text = { Text(import.message) },
            confirmButton = { TextButton(onClick = actions.dismissImportError) { Text(stringResource(R.string.ok)) } },
        )
        is ImportState.Reading -> if (state.hasTimeline) {
            AlertDialog(
                onDismissRequest = {},
                title = { Text(stringResource(R.string.reading_file, (import.progress * 100).roundToInt())) },
                text = { LinearProgressIndicator(progress = { import.progress }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = {},
            )
        }
        ImportState.Idle -> Unit
    }
}

// endregion

private const val DAY_MS = 86_400_000L

private fun yearOf(epochDay: Long): Int = java.time.LocalDate.ofEpochDay(epochDay).year
