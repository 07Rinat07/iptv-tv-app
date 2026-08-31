package com.iptv.tv.feature.player

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

@Composable
internal fun StableProgrammeDialog(
    channel: Channel?,
    programs: List<EpgProgram>,
    onPlayCatchUp: (EpgProgram) -> Unit,
    onDismiss: () -> Unit
) {
    val nowMs = remember { System.currentTimeMillis() }
    val timeZone = remember { TimeZone.getDefault() }
    val dayStarts = remember(programs, timeZone.id) {
        StableProgrammeSchedule.availableDayStarts(programs, timeZone)
    }
    var selectedDayStart by remember(programs, timeZone.id) {
        mutableStateOf(
            StableProgrammeSchedule.defaultDayStart(
                programs = programs,
                nowMs = nowMs,
                timeZone = timeZone
            )
        )
    }
    val schedule = remember(programs, selectedDayStart, timeZone.id) {
        selectedDayStart?.let { dayStart ->
            StableProgrammeSchedule.forDay(
                programs = programs,
                dayStartEpochMs = dayStart,
                timeZone = timeZone
            )
        }.orEmpty()
    }
    val listState = rememberLazyListState()

    LaunchedEffect(selectedDayStart) {
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Программа передач", fontWeight = FontWeight.Bold)
                Text(
                    channel?.name ?: "Канал не выбран",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        text = {
            if (dayStarts.isEmpty()) {
                Text("Для выбранного канала программа передач не найдена.")
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(dayStarts, key = { it }) { dayStart ->
                            val selected = selectedDayStart == dayStart
                            val label = stableProgrammeDayLabel(dayStart, nowMs, timeZone)
                            if (selected) {
                                Button(onClick = { selectedDayStart = dayStart }) {
                                    Text(label)
                                }
                            } else {
                                OutlinedButton(onClick = { selectedDayStart = dayStart }) {
                                    Text(label)
                                }
                            }
                        }
                    }

                    if (schedule.isEmpty()) {
                        Text("На выбранный день программа передач не найдена.")
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 260.dp, max = 520.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .focusGroup(),
                                state = listState,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(
                                    items = schedule,
                                    key = { program ->
                                        "${program.startEpochMs}:${program.endEpochMs}:${program.title}"
                                    }
                                ) { program ->
                                    val current = nowMs >= program.startEpochMs && nowMs < program.endEpochMs
                                    Surface(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = MaterialTheme.shapes.small,
                                        color = if (current) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                            verticalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                if (current) {
                                                    "Сейчас · ${stableRange(program)}"
                                                } else {
                                                    stableRange(program)
                                                },
                                                color = if (current) {
                                                    MaterialTheme.colorScheme.primary
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = if (current) FontWeight.Bold else FontWeight.Medium
                                            )
                                            Text(
                                                program.title,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = if (current) FontWeight.Bold else FontWeight.SemiBold,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                            program.description
                                                ?.takeIf { it.isNotBlank() }
                                                ?.let { description ->
                                                    Text(
                                                        description,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        maxLines = 2,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            when (
                                                StableCatchUpActionPolicy.state(
                                                    channel = channel,
                                                    program = program,
                                                    nowMs = nowMs
                                                )
                                            ) {
                                                StableCatchUpActionState.AVAILABLE -> {
                                                    OutlinedButton(
                                                        onClick = { onPlayCatchUp(program) },
                                                        modifier = Modifier.padding(top = 4.dp)
                                                    ) {
                                                        Text("Архив")
                                                    }
                                                }

                                                StableCatchUpActionState.UNAVAILABLE -> {
                                                    Text(
                                                        "Архив недоступен",
                                                        modifier = Modifier.padding(top = 4.dp),
                                                        color = MaterialTheme.colorScheme.outline,
                                                        style = MaterialTheme.typography.labelSmall
                                                    )
                                                }

                                                StableCatchUpActionState.HIDDEN -> Unit
                                            }
                                        }
                                    }
                                }
                            }
                            VerticalScrollControls(
                                state = listState,
                                itemCount = schedule.size,
                                modifier = Modifier.fillMaxHeight()
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}

internal fun stableProgrammeDayLabel(
    dayStartEpochMs: Long,
    nowMs: Long,
    timeZone: TimeZone = TimeZone.getDefault(),
    locale: Locale = Locale.getDefault()
): String {
    val today = StableProgrammeSchedule.startOfDay(nowMs, timeZone)
    val tomorrow = StableProgrammeSchedule.nextDayStart(today, timeZone)
    return when (dayStartEpochMs) {
        today -> "Сегодня"
        tomorrow -> "Завтра"
        else -> SimpleDateFormat("dd.MM", locale).apply {
            this.timeZone = timeZone
        }.format(Date(dayStartEpochMs))
    }
}
