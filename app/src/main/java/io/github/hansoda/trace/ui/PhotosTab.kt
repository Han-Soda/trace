package io.github.hansoda.trace.ui

import android.net.Uri
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.hansoda.trace.R
import io.github.hansoda.trace.render.Corner
import io.github.hansoda.trace.render.PhotoStyle
import java.text.NumberFormat
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Photos and videos: adding or finding them, how they show, and choosing the part of a video. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun Photos(state: ScreenState, actions: ScreenActions) {
    val photos = state.photos
    val s = state.settings
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    SectionTitle(stringResource(R.string.photos))
    Text(
        stringResource(R.string.photos_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    val adding = state.mediaAdding as? MediaAdding.Working
    val busy = adding != null || state.trimming is ClipTrim.Working
    FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = RowGap, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilledTonalButton(onClick = actions.addMedia, enabled = !busy) {
            Icon(TraceIcons.AddPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.add_photos), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = actions.findMedia, enabled = !busy && state.finding != MediaFinding.Searching) {
            Icon(TraceIcons.Search, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.find_photos), modifier = Modifier.padding(start = 8.dp))
        }
    }
    if (adding != null) {
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Text(
                stringResource(R.string.adding_photos, minOf(adding.done + 1, adding.total), adding.total),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
    (state.mediaAdding as? MediaAdding.Finished)?.let { done ->
        val parts = buildList {
            add(pluralStringResource(R.plurals.photos_added, done.added, done.added))
            if (done.otherDays > 0) add(pluralStringResource(R.plurals.photos_other_days, done.otherDays, done.otherDays))
            if (done.undated > 0) add(pluralStringResource(R.plurals.photos_undated, done.undated, done.undated))
            if (done.unreadable > 0) add(pluralStringResource(R.plurals.photos_unreadable, done.unreadable, done.unreadable))
        }
        Note(parts.joinToString(". "), onClick = actions.dismissMediaNote)
    }
    if (state.finding == MediaFinding.Denied) Note(stringResource(R.string.gallery_denied), onClick = actions.dismissFinding)
    if (photos.cards.isNotEmpty()) {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 12.dp), horizontalArrangement = RowGap) {
            for (card in photos.cards) {
                PhotoThumbnail(
                    card,
                    onRemove = { actions.removeMedia(card.id) },
                    onEdit = if (card.source != null) ({ editing = card.id }) else null,
                )
            }
        }
        if (photos.hasClips) Note(stringResource(R.string.clips_hint))
    }
    if (photos.otherDays > 0 && state.mediaAdding !is MediaAdding.Finished) {
        Note(pluralStringResource(R.plurals.photos_other_days, photos.otherDays, photos.otherDays))
    }

    Text(stringResource(R.string.photo_style), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 16.dp, bottom = 8.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = RowGap) {
        val names = mapOf(
            PhotoStyle.CARD to R.string.photo_style_card,
            PhotoStyle.MAP to R.string.photo_style_map,
            PhotoStyle.CORNER to R.string.photo_style_corner,
            PhotoStyle.POLAROID to R.string.photo_style_polaroid,
        )
        for ((style, name) in names) {
            FilterChip(
                selected = s.photoStyle == style,
                onClick = { actions.update { it.copy(photoStyle = style) } },
                label = { Text(stringResource(name)) },
            )
        }
    }
    Note(
        stringResource(
            when (s.photoStyle) {
                PhotoStyle.CARD -> R.string.photo_style_card_hint
                PhotoStyle.MAP -> R.string.photo_style_map_hint
                PhotoStyle.CORNER -> R.string.photo_style_corner_hint
                PhotoStyle.POLAROID -> R.string.photo_style_polaroid_hint
            },
        ),
    )
    if (s.photoStyle == PhotoStyle.CORNER) {
        val corners = mapOf(
            Corner.TOP_LEFT to "↖",
            Corner.TOP_RIGHT to "↗",
            Corner.BOTTOM_LEFT to "↙",
            Corner.BOTTOM_RIGHT to "↘",
        )
        Choice(Corner.entries, s.photoCorner, { corners.getValue(it) }, { corner -> actions.update { it.copy(photoCorner = corner) } }, Modifier.padding(top = 8.dp))
    }
    SwitchRow(stringResource(R.string.captions), s.captions, { on -> actions.update { it.copy(captions = on) } })
    if (photos.cards.any { !it.isClip }) {
        val number = NumberFormat.getNumberInstance(LocalConfiguration.current.locales[0]).apply { maximumFractionDigits = 1 }
        LabeledSlider(
            label = stringResource(R.string.photo_seconds),
            value = stringResource(R.string.photo_seconds_value, number.format(s.photoSeconds)),
            position = s.photoSeconds,
            onChange = { value -> actions.update { it.copy(photoSeconds = (value * 2).roundToInt() / 2f) } },
            range = 1f..4f,
        )
    }
    if (photos.hasSound) SwitchRow(stringResource(R.string.clip_sound), s.clipSound, { on -> actions.update { it.copy(clipSound = on) } })

    val clip = editing?.let { id -> photos.cards.firstOrNull { it.id == id } }
    val source = clip?.source
    if (clip != null && source != null) ClipEditor(clip, source, state.trimming, actions) { editing = null }
    when (val finding = state.finding) {
        MediaFinding.Searching, is MediaFinding.Found -> FindSheet(finding, actions)
        else -> Unit
    }
}

@Composable
private fun Note(text: String, onClick: (() -> Unit)? = null) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 8.dp).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    )
}

@Composable
private fun PhotoThumbnail(card: PhotoCard, onRemove: () -> Unit, onEdit: (() -> Unit)?) {
    Box(
        Modifier
            .size(76.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .then(if (onEdit != null) Modifier.clickable(onClickLabel = stringResource(R.string.clip_part), onClick = onEdit) else Modifier),
    ) {
        card.thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (card.isClip) {
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(5.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (onEdit != null) TraceIcons.Cut else TraceIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                Text(
                    clockText(card.seconds.toFloat()),
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(start = 3.dp),
                )
            }
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(4.dp)
                .size(24.dp)
                .clip(CircleShape)
                .background(Color(0x99000000))
                .clickable(onClickLabel = stringResource(R.string.remove), onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(TraceIcons.Close, contentDescription = stringResource(R.string.remove), tint = Color.White, modifier = Modifier.size(14.dp))
        }
    }
}

// region Finding

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FindSheet(finding: MediaFinding, actions: ScreenActions) {
    ModalBottomSheet(
        onDismissRequest = actions.dismissFinding,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.found_title), style = MaterialTheme.typography.titleLarge)
            when (finding) {
                is MediaFinding.Found -> Found(finding, actions)
                else -> Row(Modifier.padding(vertical = 40.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.finding_photos), modifier = Modifier.padding(start = 14.dp))
                }
            }
        }
    }
}

@Composable
private fun Found(found: MediaFinding.Found, actions: ScreenActions) {
    var chosen by remember(found) { mutableStateOf(found.suggested) }
    if (found.items.isEmpty()) {
        Text(
            stringResource(R.string.found_none),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 24.dp),
        )
        TextButton(onClick = actions.dismissFinding) { Text(stringResource(R.string.close)) }
        return
    }
    Text(
        stringResource(R.string.found_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            pluralStringResource(R.plurals.found_count, found.items.size, found.items.size),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { chosen = found.items.mapTo(HashSet()) { it.uri } }) { Text(stringResource(R.string.select_all)) }
        TextButton(onClick = { chosen = emptySet() }) { Text(stringResource(R.string.select_none)) }
    }
    val days = remember(found) { found.items.groupBy { it.day } }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(92.dp),
        modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((day, items) in days) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "day $day") {
                Text(day, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
            }
            items(items, key = { it.uri.toString() }) { item ->
                FoundCell(item, item.uri in chosen, actions.foundThumbnail) {
                    chosen = if (item.uri in chosen) chosen - item.uri else chosen + item.uri
                }
            }
        }
    }
    Button(
        onClick = { actions.addFound(found.items.filter { it.uri in chosen }.map { it.uri }) },
        enabled = chosen.isNotEmpty(),
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).height(48.dp),
    ) {
        Text(pluralStringResource(R.plurals.found_add, chosen.size, chosen.size))
    }
}

@Composable
private fun FoundCell(item: FoundMedia, selected: Boolean, load: suspend (FoundMedia) -> ImageBitmap?, onToggle: () -> Unit) {
    val thumbnail by produceState<ImageBitmap?>(null, item) { value = load(item) }
    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onToggle),
    ) {
        thumbnail?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (!selected) Box(Modifier.fillMaxSize().background(Color(0x66FFFFFF)))
        if (item.video) {
            Text(
                clockText(item.durationMs / 1000f),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0x99000000))
                    .padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
        Box(
            Modifier
                .align(Alignment.TopEnd)
                .padding(5.dp)
                .size(22.dp)
                .clip(CircleShape)
                .background(if (selected) MaterialTheme.colorScheme.primary else Color(0x66000000)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(TraceIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(16.dp))
        }
    }
}

// endregion

// region Clip editor

/** Chooses which part of a clip's video to show: up to [MAX_PART] seconds of it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ClipEditor(card: PhotoCard, source: String, trimming: ClipTrim?, actions: ScreenActions, onClose: () -> Unit) {
    val duration = (card.sourceMs / 1000f).coerceAtLeast(MIN_PART)
    var part by remember(card.id) {
        val start = card.startMs / 1000f
        mutableStateOf(start..min(duration, start + card.seconds.toFloat()))
    }
    val working = (trimming as? ClipTrim.Working)?.takeIf { it.id == card.id }
    val failed = trimming is ClipTrim.Failed && trimming.id == card.id
    // Done once the new part is in.
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(working, failed) {
        if (working != null) started = true
        if (started && working == null && !failed) onClose()
    }
    ModalBottomSheet(
        onDismissRequest = {
            if (working == null) {
                actions.dismissTrimError()
                onClose()
            }
        },
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(stringResource(R.string.clip_part), style = MaterialTheme.typography.titleLarge)
            VideoPart(
                source, part.start, part.endInclusive,
                Modifier.padding(top = 12.dp).fillMaxWidth().height(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.Black),
            )
            RangeSlider(
                value = part,
                onValueChange = { next -> part = limitPart(next, part, duration) },
                valueRange = 0f..duration,
                enabled = working == null,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row {
                Text(
                    clockText(part.start) + " – " + clockText(part.endInclusive),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(R.string.photo_seconds_value, (((part.endInclusive - part.start) * 10).roundToInt() / 10f).toString()),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Note(stringResource(R.string.clip_part_hint))
            if (failed) {
                Text(
                    stringResource(R.string.trim_failed),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (working != null) {
                Text(stringResource(R.string.trimming), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                LinearProgressIndicator(progress = { working.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            }
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = {
                        actions.dismissTrimError()
                        onClose()
                    },
                    enabled = working == null,
                ) { Text(stringResource(R.string.cancel)) }
                Button(
                    onClick = {
                        actions.dismissTrimError()
                        actions.trimClip(card.id, (part.start * 1000).roundToInt().toLong(), ((part.endInclusive - part.start) * 1000).roundToInt().toLong())
                    },
                    enabled = working == null,
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.use_part)) }
            }
        }
    }
}

/** The video at [source], playing [start] to [end] seconds over and over, with its sound. */
@Composable
private fun VideoPart(source: String, start: Float, end: Float, modifier: Modifier) {
    var player by remember { mutableStateOf<VideoView?>(null) }
    var broken by remember { mutableStateOf(false) }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(
            factory = { context ->
                VideoView(context).apply {
                    // A video that can't be played says so here, not in a dialog of its own.
                    setOnErrorListener { _, _, _ ->
                        broken = true
                        true
                    }
                    setVideoURI(Uri.parse(source))
                    player = this
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
        if (broken) {
            Text(stringResource(R.string.video_unavailable), color = Color.White, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(24.dp))
        }
    }
    LaunchedEffect(player, start, end) {
        val view = player ?: return@LaunchedEffect
        view.seekTo((start * 1000).roundToInt())
        view.start()
        while (true) {
            delay(100)
            val at = view.currentPosition
            if (at >= end * 1000 || at < start * 1000 - 600) {
                view.seekTo((start * 1000).roundToInt())
                if (!view.isPlaying) view.start()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { player?.stopPlayback() }
    }
}

/** [next] kept between [MIN_PART] and [MAX_PART] seconds long, moving the end that wasn't dragged. */
private fun limitPart(next: ClosedFloatingPointRange<Float>, previous: ClosedFloatingPointRange<Float>, duration: Float): ClosedFloatingPointRange<Float> {
    val shortest = min(MIN_PART, duration)
    val longest = min(MAX_PART, duration)
    var start = next.start
    var end = next.endInclusive
    val movedStart = start != previous.start
    if (end - start > longest) if (movedStart) end = start + longest else start = end - longest
    if (end - start < shortest) if (movedStart) start = end - shortest else end = start + shortest
    if (start < 0) {
        end -= start
        start = 0f
    }
    if (end > duration) {
        start -= end - duration
        end = duration
    }
    return start.coerceAtLeast(0f)..end
}

private const val MIN_PART = 1f
private const val MAX_PART = 10f

// endregion
