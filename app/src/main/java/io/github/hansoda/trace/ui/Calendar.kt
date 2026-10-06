package io.github.hansoda.trace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.hansoda.trace.R
import io.github.hansoda.trace.route.DayActivity
import io.github.hansoda.trace.route.DaySelection
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

private enum class PickMode { RANGE, DAYS }

/**
 * Picks the days for a video: one stretch of days, or any days at all. Days with travel get a
 * dot; days without any history are dimmed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarSheet(
    selection: DaySelection,
    activity: DayActivity?,
    firstDay: Long,
    lastDay: Long,
    onApply: (DaySelection) -> Unit,
    onDismiss: () -> Unit,
) {
    var mode by remember { mutableStateOf(if (selection.isRange) PickMode.RANGE else PickMode.DAYS) }
    var rangeStart by remember { mutableStateOf<Long?>(selection.first) }
    var rangeEnd by remember { mutableStateOf<Long?>(selection.last) }
    var days by remember { mutableStateOf<DaySelection?>(selection) }

    val pending: DaySelection? = when (mode) {
        PickMode.RANGE -> rangeStart?.let { start -> DaySelection.range(start, rangeEnd ?: start) }
        PickMode.DAYS -> days
    }

    fun tap(day: Long) {
        when (mode) {
            PickMode.RANGE -> {
                val start = rangeStart
                if (start == null || rangeEnd != null) {
                    rangeStart = day
                    rangeEnd = null
                } else {
                    rangeStart = minOf(start, day)
                    rangeEnd = maxOf(start, day)
                }
            }
            PickMode.DAYS -> days = days?.toggled(day) ?: DaySelection.range(day, day)
        }
    }

    fun switchTo(next: PickMode) {
        if (next == mode) return
        when (next) {
            PickMode.RANGE -> {
                rangeStart = days?.first
                rangeEnd = days?.last
            }
            PickMode.DAYS -> days = pending
        }
        mode = next
    }

    val from = YearMonth.from(LocalDate.ofEpochDay(minOf(firstDay, selection.first)))
    val to = YearMonth.from(LocalDate.ofEpochDay(maxOf(lastDay, selection.last)))
    val months = remember(from, to) {
        generateSequence(from) { it.plusMonths(1) }.takeWhile { !it.isAfter(to) }.toList()
    }
    val startMonth = months.indexOf(YearMonth.from(LocalDate.ofEpochDay(selection.last))).coerceAtLeast(0)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = startMonth)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding()) {
            Text(stringResource(R.string.choose_dates), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            val names = mapOf(PickMode.RANGE to stringResource(R.string.pick_range), PickMode.DAYS to stringResource(R.string.pick_days))
            Choice(PickMode.entries, mode, { names.getValue(it) }, ::switchTo)
            Text(
                pending?.let { chosen ->
                    Formats.selection(LocalContext.current, chosen) + "  ·  " + pluralStringResource(R.plurals.days, chosen.dayCount, chosen.dayCount)
                } ?: stringResource(if (mode == PickMode.RANGE) R.string.pick_range_hint else R.string.pick_days_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp, bottom = 4.dp),
            )
            WeekdayHeader()
            LazyColumn(state = list, modifier = Modifier.weight(1f, fill = false).height(420.dp)) {
                items(months, key = { it.toString() }) { month ->
                    Month(month, pending, mode, rangeStart, rangeEnd, activity, ::tap)
                }
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = {
                        rangeStart = null
                        rangeEnd = null
                        days = null
                    },
                    enabled = pending != null,
                ) { Text(stringResource(R.string.clear)) }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Button(onClick = { pending?.let(onApply) }, enabled = pending != null, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.apply))
                }
            }
        }
    }
}

@Composable
private fun WeekdayHeader() {
    val locale = Locale.getDefault()
    val first = WeekFields.of(locale).firstDayOfWeek
    Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        for (k in 0 until 7) {
            Text(
                first.plus(k.toLong()).getDisplayName(TextStyle.NARROW, locale),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Month(
    month: YearMonth,
    pending: DaySelection?,
    mode: PickMode,
    rangeStart: Long?,
    rangeEnd: Long?,
    activity: DayActivity?,
    onTap: (Long) -> Unit,
) {
    val locale = Locale.getDefault()
    val title = remember(month, locale) { month.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale)).replaceFirstChar { it.titlecase(locale) } }
    val firstDayOfWeek = WeekFields.of(locale).firstDayOfWeek
    val leading = (month.atDay(1).dayOfWeek.value - firstDayOfWeek.value + 7) % 7
    val length = month.lengthOfMonth()
    Column(Modifier.padding(top = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        val cells = leading + length
        for (row in 0 until (cells + 6) / 7) {
            Row(Modifier.fillMaxWidth()) {
                for (column in 0 until 7) {
                    val index = row * 7 + column - leading
                    Box(Modifier.weight(1f).aspectRatio(1f), contentAlignment = Alignment.Center) {
                        if (index in 0 until length) {
                            val day = month.atDay(index + 1).toEpochDay()
                            val chosen = pending?.contains(day) == true
                            val end = day == rangeStart || day == (rangeEnd ?: rangeStart)
                            DayCell(
                                number = index + 1,
                                look = when {
                                    !chosen -> CellLook.PLAIN
                                    mode == PickMode.DAYS || end -> CellLook.CHOSEN
                                    else -> CellLook.BETWEEN
                                },
                                hasData = activity?.hasData(day) ?: true,
                                moved = activity?.moved(day) == true,
                                onClick = { onTap(day) },
                            )
                        }
                    }
                }
            }
        }
    }
}

private enum class CellLook { PLAIN, BETWEEN, CHOSEN }

@Composable
private fun DayCell(number: Int, look: CellLook, hasData: Boolean, moved: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val background = when (look) {
        CellLook.CHOSEN -> colors.primary
        CellLook.BETWEEN -> colors.secondaryContainer
        CellLook.PLAIN -> androidx.compose.ui.graphics.Color.Transparent
    }
    val ink = when {
        look == CellLook.CHOSEN -> colors.onPrimary
        look == CellLook.BETWEEN -> colors.onSecondaryContainer
        hasData -> colors.onSurface
        else -> colors.onSurface.copy(alpha = 0.35f)
    }
    Box(
        Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(CircleShape)
            .background(background)
            .clickable(onClick = onClick)
            .semantics { selected = look != CellLook.PLAIN },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number.toString(),
            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (moved) FontWeight.Medium else FontWeight.Normal),
            color = ink,
        )
        if (moved) {
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 5.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(if (look == CellLook.CHOSEN) colors.onPrimary else colors.primary),
            )
        }
    }
}
