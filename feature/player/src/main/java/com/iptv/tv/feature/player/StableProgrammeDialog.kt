package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

internal const val STABLE_PROGRAMME_PANEL_WIDTH_FRACTION = 0.46f
internal const val STABLE_PROGRAMME_PANEL_MAX_WIDTH_DP = 560f

internal fun stableProgrammePanelWidthDp(screenWidthDp: Float): Float =
    (screenWidthDp * STABLE_PROGRAMME_PANEL_WIDTH_FRACTION)
        .coerceAtMost(STABLE_PROGRAMME_PANEL_MAX_WIDTH_DP)

@Composable
internal fun StableProgrammeDialog(
    channel: Channel?,
    programs: List<EpgProgram>,
    onDismiss: () -> Unit
) {
    val nowMs = System.currentTimeMillis()
    val schedule = StableProgrammeSchedule.visible(programs, nowMs)
    val listState = rememberLazyListState()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false
        )
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val panelWidth = stableProgrammePanelWidthDp(maxWidth.value).dp
            Surface(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp)
                    .fillMaxHeight()
                    .width(panelWidth)
                    .focusGroup()
                    .tvFocusOutline(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.surface,
                contentColor = MaterialTheme.colorScheme.onSurface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                tonalElevation = 6.dp,
                shadowElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                "Программа передач",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                channel?.name ?: "Канал не выбран",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = onDismiss,
                            modifier = Modifier.tvFocusOutline()
                        ) {
                            Text("Закрыть")
                        }
                    }

                    if (schedule.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Для выбранного канала программа пока не найдена.",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                            verticalAlignment = Alignment.Top,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                }
            }
        }
    }
}
