package io.github.hansoda.trace.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.Locale

/** Section heading: small, quiet, with room above. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(top = 28.dp, bottom = 6.dp),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A slider with its label on the left and current value on the right. */
@Composable
fun LabeledSlider(
    label: String,
    value: String,
    position: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    hint: String? = null,
    range: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Slider(value = position, onValueChange = onChange, valueRange = range, steps = steps)
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
    }
}

/** A whole-row switch. */
@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** One-of-a-few choice as connected buttons. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> Choice(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
        options.forEachIndexed { index, option ->
            SegmentedButton(
                selected = option == selected,
                onClick = { onSelect(option) },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                icon = {},
                label = { Text(label(option), maxLines = 1, overflow = TextOverflow.Ellipsis) },
            )
        }
    }
}

/** A round colour swatch; selected ones get a ring. */
@Composable
fun Swatch(color: Color, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, label: String? = null) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .then(if (selected) Modifier.border(BorderStroke(2.dp, ring), CircleShape) else Modifier)
            .padding(if (selected) 5.dp else 3.dp)
            .clip(CircleShape)
            .background(color)
            .border(BorderStroke(1.dp, ring.copy(alpha = 0.12f)), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                TraceIcons.Check,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (color.luminance() > 0.6f) Color.Black else Color.White,
            )
        }
    }
}

/** The swatch that opens the colour picker: a little colour wheel with a plus. */
@Composable
fun PickerSwatch(onClick: () -> Unit, label: String, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick)
            .padding(3.dp)
            .clip(CircleShape)
            .background(Brush.sweepGradient(HUES)),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(20.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            Icon(TraceIcons.Add, contentDescription = label, modifier = Modifier.size(14.dp))
        }
    }
}

/** A horizontal slider over a gradient, for picking hue, saturation and brightness. */
@Composable
fun GradientSlider(value: Float, colors: List<Color>, onChange: (Float) -> Unit, modifier: Modifier = Modifier) {
    val change by rememberUpdatedState(onChange)
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .fillMaxWidth()
            .height(36.dp)
            .pointerInput(Unit) { detectTapGestures { change(fraction(it.x, size.width)) } }
            .pointerInput(Unit) { detectHorizontalDragGestures { pointer, _ -> change(fraction(pointer.position.x, size.width)) } },
    ) {
        val thumb = size.height * 0.4f
        val track = size.height * 0.45f
        drawRoundRect(
            brush = Brush.horizontalGradient(colors, startX = thumb, endX = size.width - thumb),
            topLeft = Offset(0f, (size.height - track) / 2),
            size = Size(size.width, track),
            cornerRadius = CornerRadius(track / 2),
        )
        val center = Offset(thumb + value.coerceIn(0f, 1f) * (size.width - 2 * thumb), size.height / 2)
        drawCircle(Color.White, radius = thumb, center = center)
        drawCircle(outline, radius = thumb, center = center, style = Stroke(width = 1.5.dp.toPx()))
    }
}

private fun fraction(x: Float, width: Int): Float = (x / width.coerceAtLeast(1)).coerceIn(0f, 1f)

val HUES = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

/** Even spacing for rows of controls. */
val RowGap = Arrangement.spacedBy(8.dp)

/** "0:07" */
fun clockText(seconds: Float): String {
    val whole = seconds.toInt().coerceAtLeast(0)
    return "%d:%02d".format(Locale.ROOT, whole / 60, whole % 60)
}
