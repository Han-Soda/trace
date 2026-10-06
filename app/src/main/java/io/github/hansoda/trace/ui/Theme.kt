package io.github.hansoda.trace.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Quiet, nearly monochrome colours, so the route's colour is the only accent on screen. */
private val Light = lightColorScheme(
    primary = Color(0xFF1B1B1F),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE6E5E1),
    onPrimaryContainer = Color(0xFF1B1B1F),
    secondary = Color(0xFF5F5F5A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE6E5E1),
    onSecondaryContainer = Color(0xFF1B1B1F),
    background = Color(0xFFFAFAF8),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFAFAF8),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFEDECE8),
    onSurfaceVariant = Color(0xFF5F5F5A),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F4F1),
    surfaceContainer = Color(0xFFF0EFEB),
    surfaceContainerHigh = Color(0xFFEAE9E5),
    surfaceContainerHighest = Color(0xFFE4E3DF),
    outline = Color(0xFFC4C3BE),
    outlineVariant = Color(0xFFDDDCD7),
    error = Color(0xFFB3261E),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEDEDEA),
    onPrimary = Color(0xFF151517),
    primaryContainer = Color(0xFF2D2D30),
    onPrimaryContainer = Color(0xFFEDEDEA),
    secondary = Color(0xFFB2B2AD),
    onSecondary = Color(0xFF151517),
    secondaryContainer = Color(0xFF2D2D30),
    onSecondaryContainer = Color(0xFFEDEDEA),
    background = Color(0xFF111113),
    onBackground = Color(0xFFEDEDEA),
    surface = Color(0xFF111113),
    onSurface = Color(0xFFEDEDEA),
    surfaceVariant = Color(0xFF26262A),
    onSurfaceVariant = Color(0xFFA7A7A2),
    surfaceContainerLowest = Color(0xFF0C0C0E),
    surfaceContainerLow = Color(0xFF17171A),
    surfaceContainer = Color(0xFF1C1C1F),
    surfaceContainerHigh = Color(0xFF232326),
    surfaceContainerHighest = Color(0xFF2B2B2E),
    outline = Color(0xFF56565B),
    outlineVariant = Color(0xFF3A3A3E),
    error = Color(0xFFFFB4AB),
)

@Composable
fun TraceTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
