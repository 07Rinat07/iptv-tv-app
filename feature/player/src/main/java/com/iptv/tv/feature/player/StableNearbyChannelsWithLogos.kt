package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import kotlinx.coroutines.launch

@Composable
internal fun StableNearbyChannelsWithLogos(
    channels: List<Channel>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelectChannel: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    "Каналы рядом",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    "${channels.size} доступно",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(
                                StableChannelNavigation.pageTargetIndex(
                                    currentIndex = listState.firstVisibleItemIndex,
                                    itemCount = channels.size,
                                    pageSize = 4,
                                    direction = -1
                                )
                            )
                        }
                    },
                    enabled = listState.canScrollBackward
                ) { Text("◀") }
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            listState.animateScrollToItem(
                                StableChannelNavigation.pageTargetIndex(
                                    currentIndex = listState.firstVisibleItemIndex,
                                    itemCount = channels.size,
                                    pageSize = 4,
                                    direction = 1
                                )
                            )
                        }
                    },
                    enabled = listState.canScrollForward
                ) { Text("▶") }
            }

            LazyRow(
                modifier = Modifier.fillMaxWidth().focusGroup(),
                state = listState,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(channels, key = { it.id }) { channel ->
                    val nowMs = System.currentTimeMillis()
                    val programs = epgByChannel[channel.id].orEmpty()
                    val current = stableCurrentProgram(programs, nowMs)
                    val next = stableNextProgram(programs, current, nowMs)
                    val p2pStatus = if (PlayerP2pDescriptor.detect(channel.streamUrl) != null) {
                        p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[channel.id])
                    } else {
                        null
                    }

                    Surface(
                        modifier = Modifier
                            .width(220.dp)
                            .tvFocusOutline()
                            .clickable { onSelectChannel(channel.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 7.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            if (!channel.logo.isNullOrBlank()) {
                                AsyncImage(
                                    model = channel.logo,
                                    contentDescription = channel.name,
                                    modifier = Modifier.size(34.dp)
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    channel.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    current?.let { "Сейчас ${stableRange(it)} · ${it.title}" }
                                        ?: "Программа не найдена",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                current?.let { program ->
                                    LinearProgressIndicator(
                                        progress = { stableProgramProgress(program, nowMs) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                                next?.let {
                                    Text(
                                        "Далее ${stableTime(it.startEpochMs)} · ${it.title}",
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (p2pStatus != null) {
                                    Text(
                                        p2pStatus,
                                        color = MaterialTheme.colorScheme.primary,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
