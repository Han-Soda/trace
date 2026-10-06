package io.github.hansoda.trace.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.hansoda.trace.R
import io.github.hansoda.trace.data.Geo
import io.github.hansoda.trace.render.Look
import io.github.hansoda.trace.render.MapPainter
import io.github.hansoda.trace.render.MapView
import io.github.hansoda.trace.render.RoutePainter
import io.github.hansoda.trace.route.RangeData
import io.github.hansoda.trace.tiles.TileStore
import kotlin.math.max
import kotlin.math.min

/**
 * Full-screen map of every point on the chosen days, for removing the ones GPS got wrong. Tap
 * a point to select it, tap another to select everything between, then remove. Points that
 * look wrong are ringed, and "Next likely error" jumps to them one by one.
 */
@Composable
fun RouteEditor(
    data: RangeData?,
    suspects: IntArray,
    look: Look,
    tiles: TileStore,
    removedPoints: Int,
    canUndo: Boolean,
    onRemove: (data: RangeData, from: Int, to: Int) -> Unit,
    onUndo: () -> Unit,
    onRestoreAll: () -> Unit,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(56.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(TraceIcons.Close, contentDescription = stringResource(R.string.close)) }
                    Text(stringResource(R.string.edit_points_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                    IconButton(onClick = onUndo, enabled = canUndo) { Icon(TraceIcons.Undo, contentDescription = stringResource(R.string.undo)) }
                }
                if (data == null || data.size == 0) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            stringResource(R.string.no_movement),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    Editor(data, suspects, look, tiles, removedPoints, onRemove, onRestoreAll)
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Editor(
    data: RangeData,
    suspects: IntArray,
    look: Look,
    tiles: TileStore,
    removedPoints: Int,
    onRemove: (data: RangeData, from: Int, to: Int) -> Unit,
    onRestoreAll: () -> Unit,
) {
    val density = LocalDensity.current.density
    val painter = remember(density) { RoutePainter(density) }
    val map = remember { MapPainter() }
    val tileVersion by tiles.version.collectAsState()
    var size by remember { mutableStateOf(IntSize.Zero) }
    var view by remember { mutableStateOf<MapView?>(null) }
    // A removal changes the points, so a selection never outlives its data.
    var selection by remember(data) { mutableStateOf<IntRange?>(null) }
    var nextSuspect by remember(data) { mutableIntStateOf(0) }
    val current by rememberUpdatedState(data)

    fun fit(width: Int, height: Int): MapView {
        var minX = Double.POSITIVE_INFINITY
        var minY = Double.POSITIVE_INFINITY
        var maxX = Double.NEGATIVE_INFINITY
        var maxY = Double.NEGATIVE_INFINITY
        for (i in 0 until data.size) {
            minX = min(minX, data.x[i])
            maxX = max(maxX, data.x[i])
            minY = min(minY, data.y[i])
            maxY = max(maxY, data.y[i])
        }
        val aspect = width.toDouble() / height
        val across = max((maxX - minX) * 1.15, (maxY - minY) * 1.15 * aspect)
        return MapView((minX + maxX) / 2, (minY + maxY) / 2, max(across, closest((minY + maxY) / 2)).coerceAtMost(1.5))
    }

    fun showSuspect() {
        if (suspects.isEmpty() || size.width == 0) return
        val i = suspects[nextSuspect % suspects.size]
        nextSuspect++
        if (i >= data.size) return
        // Wide enough to see where the route came from and went on to.
        var reach = closest(data.y[i]) * 2
        for (j in listOf(i - 1, i + 1)) {
            if (j in 0 until data.size) reach = max(reach, kotlin.math.hypot(data.x[j] - data.x[i], data.y[j] - data.y[i]) * 3)
        }
        view = MapView(data.x[i], data.y[i], reach.coerceAtMost(1.5))
        selection = i..i
    }

    Box(
        Modifier
            .weight(1f)
            .fillMaxWidth()
            .onSizeChanged { measured ->
                size = measured
                if (view == null && measured.width > 0 && measured.height > 0) view = fit(measured.width, measured.height)
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val v = view ?: return@detectTransformGestures
                    view = moved(v, size, centroid, pan, zoom)
                }
            }
            .pointerInput(data) {
                detectTapGestures(
                    onTap = { at ->
                        val v = view ?: return@detectTapGestures
                        val points = current
                        val hit = painter.pointAt(at.x, at.y, 28 * density, size.width, size.height, v, points)
                        val chosen = selection
                        selection = when {
                            hit < 0 -> null
                            chosen == null || chosen.first != chosen.last -> hit..hit
                            else -> min(chosen.first, hit)..max(chosen.first, hit)
                        }
                    },
                    onDoubleTap = { at ->
                        val v = view ?: return@detectTapGestures
                        view = moved(v, size, at, Offset.Zero, 2f)
                    },
                )
            },
    ) {
        NativeCanvas(Modifier.fillMaxSize()) { canvas, width, height ->
            val v = view ?: return@NativeCanvas
            if (tileVersion < 0) return@NativeCanvas
            canvas.drawColor(look.map.background)
            map.draw(canvas, width, height, v.x, v.y, v.width, look.map.tileSet(look.labels), tiles)
            painter.draw(canvas, width, height, v, data, look, suspects, selection)
        }
    }

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
        val chosen = selection
        if (chosen == null) {
            Text(
                stringResource(R.string.edit_points_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (suspects.isNotEmpty()) {
                OutlinedButton(onClick = ::showSuspect, modifier = Modifier.padding(top = 10.dp)) {
                    Text(pluralStringResource(R.plurals.likely_errors, suspects.size, suspects.size))
                }
            }
        } else {
            val count = chosen.last - chosen.first + 1
            val first = Formats.moment(data.times[chosen.first], data.offsets[chosen.first])
            Text(
                if (count == 1) first else first + " – " + Formats.moment(data.times[chosen.last], data.offsets[chosen.last]),
                style = MaterialTheme.typography.bodyLarge,
            )
            if (count == 1) {
                Text(
                    stringResource(R.string.edit_points_extend),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { onRemove(data, chosen.first, chosen.last) }) {
                    Icon(TraceIcons.Delete, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                    Text(pluralStringResource(R.plurals.remove_points, count, count))
                }
                TextButton(onClick = { selection = null }) { Text(stringResource(R.string.cancel)) }
                if (suspects.isNotEmpty()) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = ::showSuspect) { Icon(TraceIcons.Forward, contentDescription = stringResource(R.string.next_likely_error)) }
                }
            }
        }
        if (removedPoints > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    pluralStringResource(R.plurals.points_removed, removedPoints, removedPoints),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onRestoreAll) { Text(stringResource(R.string.restore_all)) }
            }
        }
    }
}

/** The view after panning by [pan] pixels and zooming by [zoom] around [centroid]. */
private fun moved(view: MapView, size: IntSize, centroid: Offset, pan: Offset, zoom: Float): MapView {
    if (size.width == 0) return view
    val scale = size.width / view.width
    val panned = MapView(view.x - pan.x / scale, view.y - pan.y / scale, view.width)
    val width = (view.width / zoom).coerceIn(closest(view.y) / 4, 1.5)
    val left = panned.x - panned.width / 2
    val top = panned.y - panned.width * size.height / size.width / 2
    val worldX = left + centroid.x / scale
    val worldY = top + centroid.y / scale
    val newLeft = worldX - centroid.x * width / size.width
    val newTop = worldY - centroid.y * width / size.width
    return MapView(newLeft + width / 2, newTop + width * size.height / size.width / 2, width)
}

/** The closest the editor zooms: about 300 m across. */
private fun closest(y: Double): Double = 300 * Geo.worldPerMeter(y)
