package io.github.hansoda.trace.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.hansoda.trace.R
import io.github.hansoda.trace.settings.Quality
import io.github.hansoda.trace.settings.Units

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(state: ScreenState, actions: ScreenActions, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .navigationBarsPadding()
                .padding(bottom = 24.dp),
        ) {
            Text(stringResource(R.string.settings), style = MaterialTheme.typography.titleLarge)
            MapKey(state, actions)

            SectionTitle(stringResource(R.string.units))
            Choice(Units.entries, state.settings.units, { if (it == Units.MILES) "mi" else "km" }, { units -> actions.update { it.copy(units = units) } })

            SectionTitle(stringResource(R.string.video_quality))
            Choice(Quality.entries, state.settings.quality, { it.id }, { quality -> actions.update { it.copy(quality = quality) } })
            Spacer(Modifier.height(8.dp))
            val fpsLabels = listOf(30, 60).associateWith { stringResource(R.string.fps_value, it) }
            Choice(fpsLabels.keys.toList(), state.settings.fps, { fpsLabels.getValue(it) }, { fps -> actions.update { it.copy(fps = fps) } })

            SectionTitle(stringResource(R.string.storage))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.map_cache, state.cacheSize), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                TextButton(onClick = actions.clearCache) { Text(stringResource(R.string.clear)) }
            }
            if (state.timelineName != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(state.timelineName, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            pluralStringResource(R.plurals.locations, state.timelinePoints, state.timelinePoints),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = actions.openFiles) { Text(stringResource(R.string.replace)) }
                    TextButton(onClick = actions.removeTimeline) { Text(stringResource(R.string.remove)) }
                }
                // For exporting newer history to replace this file with.
                TextButton(onClick = actions.openTimelineExport) {
                    Icon(TraceIcons.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.open_timeline_settings), modifier = Modifier.padding(start = 8.dp))
                }
            }

            SectionTitle(stringResource(R.string.about))
            Text(
                stringResource(R.string.about_text, state.version),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MapKey(state: ScreenState, actions: ScreenActions) {
    SectionTitle(stringResource(R.string.map_key))
    Text(
        stringResource(R.string.map_key_explained),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = actions.openKeyPage, modifier = Modifier.padding(top = 2.dp)) {
        Text(stringResource(R.string.get_free_key))
    }
    var visible by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = state.settings.cartoKey,
        onValueChange = { key -> actions.update { it.copy(cartoKey = key.trim()) } },
        label = { Text(stringResource(R.string.carto_key)) },
        singleLine = true,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = {
            IconButton(onClick = { visible = !visible }) {
                Icon(
                    if (visible) TraceIcons.Hidden else TraceIcons.Visible,
                    contentDescription = stringResource(if (visible) R.string.hide_key else R.string.show_key),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedButton(onClick = actions.testKey, enabled = state.keyTest != KeyTest.Testing) {
            Text(stringResource(R.string.test_key))
        }
        when (val test = state.keyTest) {
            KeyTest.Idle -> Unit
            KeyTest.Testing -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            KeyTest.Failed -> Text(stringResource(R.string.key_test_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            is KeyTest.Loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(test.tile, contentDescription = null, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
                Text(
                    stringResource(R.string.key_test_loaded),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
