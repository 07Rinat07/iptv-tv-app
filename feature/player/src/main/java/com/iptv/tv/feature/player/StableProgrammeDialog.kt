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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

@Composable
internal fun StableProgrammeDialog(
    channel: Channel?,
    programs: List<EpgProgram>,
    onDismiss: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val schedule = StableProgrammeSchedule.visible(programs, nowMs)
    val listState = rememberLazyListState()

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
            if (schedule.isEmpty()) {
                Text("Для выбранного канала программа передач не найдена.")
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 260.dp, max = 560.dp),
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
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Закрыть")
            }
        }
    )
}
