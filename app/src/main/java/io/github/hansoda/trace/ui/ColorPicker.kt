package io.github.hansoda.trace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import io.github.hansoda.trace.R
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** Picks any route colour by hue, saturation and brightness, or by hex code. */
@Composable
fun ColorPickerDialog(initial: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val start = remember(initial) { toHsv(initial) }
    var hue by remember { mutableFloatStateOf(start[0]) }
    var saturation by remember { mutableFloatStateOf(start[1]) }
    var brightness by remember { mutableFloatStateOf(start[2]) }
    val color = Color.hsv(hue * 360f, saturation, brightness)
    var hex by remember { mutableStateOf(hexOf(color.toArgb())) }

    fun fromSliders() {
        hex = hexOf(Color.hsv(hue * 360f, saturation, brightness).toArgb())
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.custom_color)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(16.dp)).background(color))
                GradientSlider(hue, HUES, { hue = it; fromSliders() })
                GradientSlider(saturation, listOf(Color.hsv(hue * 360f, 0f, brightness), Color.hsv(hue * 360f, 1f, brightness)), { saturation = it; fromSliders() })
                GradientSlider(brightness, listOf(Color.Black, Color.hsv(hue * 360f, saturation, 1f)), { brightness = it; fromSliders() })
                OutlinedTextField(
                    value = hex,
                    onValueChange = { text ->
                        hex = text.take(7)
                        parseHex(text)?.let { argb ->
                            val hsv = toHsv(argb)
                            hue = hsv[0]
                            saturation = hsv[1]
                            brightness = hsv[2]
                        }
                    },
                    label = { Text(stringResource(R.string.hex_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(color.toArgb()) }) { Text(stringResource(R.string.use_color)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    )
}

/** Hue, saturation and value, each 0–1. */
internal fun toHsv(argb: Int): FloatArray {
    val r = (argb shr 16 and 0xFF) / 255f
    val g = (argb shr 8 and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    val high = max(r, max(g, b))
    val low = min(r, min(g, b))
    val delta = high - low
    val hue = when {
        delta == 0f -> 0f
        high == r -> ((g - b) / delta).mod(6f)
        high == g -> (b - r) / delta + 2f
        else -> (r - g) / delta + 4f
    } / 6f
    return floatArrayOf(hue, if (high == 0f) 0f else delta / high, high)
}

internal fun hexOf(argb: Int): String = "#%06X".format(Locale.ROOT, argb and 0xFFFFFF)

internal fun parseHex(text: String): Int? {
    val digits = text.trim().removePrefix("#")
    if (digits.length != 6) return null
    return digits.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
}
