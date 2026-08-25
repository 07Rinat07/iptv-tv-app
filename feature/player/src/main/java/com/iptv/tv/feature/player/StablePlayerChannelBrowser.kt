package com.iptv.tv.feature.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
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
internal fun StableNearbyChannelsReplacement(
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
                            .width(176.dp)
                            .tvFocusOutline()
                            .clickable { onSelectChannel(channel.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        tonalElevation = 0.dp
                    ) {
                        Column(
                            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
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
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            current?.let { program ->
                                LinearProgressIndicator(
                                    progress = { stableProgramProgress(program, nowMs) },
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Text(
                                next?.let { "Далее ${stableTime(it.startEpochMs)} · ${it.title}" }
                                    ?: "Далее · данных нет",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
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

@Composable
internal fun StableChannelBrowserReplacement(
    modifier: Modifier,
    query: String,
    onQueryChange: (String) -> Unit,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenGroups: () -> Unit
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier.fillMaxSize().padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Каналы",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${channels.size} доступно",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(onClick = onOpenGroups, modifier = Modifier.tvFocusOutline()) {
                    Text("Группы")
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск канала") },
                singleLine = true
            )

            StableChannelListReplacement(
                modifier = Modifier.fillMaxWidth().weight(1f),
                channels = channels,
                selectedChannelId = selectedChannelId,
                favoriteIds = favoriteIds,
                epgByChannel = epgByChannel,
                onSelect = onSelect,
                onToggleFavorite = onToggleFavorite
            )
        }
    }
}

@Composable
private fun StableChannelListReplacement(
    modifier: Modifier,
    channels: List<Channel>,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val focusChannelId = remember(channels, selectedChannelId) {
        StableChannelNavigation.selectionAfterFilter(
            visibleChannelIds = channels.map { it.id },
            previousSelectedChannelId = selectedChannelId
        )
    }

    LaunchedEffect(focusChannelId, channels) {
        val index = channels.indexOfFirst { it.id == focusChannelId }
        if (index >= 0) listState.animateScrollToItem(index)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxHeight()
            .focusGroup()
            .stablePagedListNavigation(
                state = listState,
                itemCount = channels.size
            ),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        items(channels, key = { it.id }) { channel ->
            val nowMs = System.currentTimeMillis()
            val programs = epgByChannel[channel.id].orEmpty()
            val current = stableCurrentProgram(programs, nowMs)
            val next = stableNextProgram(programs, current, nowMs)
            val selected = channel.id == selectedChannelId
            val p2pStatus = if (PlayerP2pDescriptor.detect(channel.streamUrl) != null) {
                p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[channel.id])
            } else {
                null
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .tvFocusOutline()
                    .stableSelectedFocus(channel.id == focusChannelId)
                    .clickable { onSelect(channel.id) },
                color = if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                border = BorderStroke(
                    width = if (selected) 2.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                ),
                tonalElevation = 0.dp,
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AsyncImage(
                        model = channel.logo,
                        contentDescription = channel.name,
                        modifier = Modifier.size(36.dp)
                    )
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            channel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                        )
                        Text(
                            current?.let { "Сейчас ${stableRange(it)} · ${it.title}" }
                                ?: "Программа не найдена",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        current?.let { program ->
                            LinearProgressIndicator(
                                progress = { stableProgramProgress(program, nowMs) },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Text(
                            next?.let { "Далее ${stableTime(it.startEpochMs)} · ${it.title}" }
                                ?: "Далее · данных нет",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
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
                    OutlinedButton(
                        onClick = { onToggleFavorite(channel.id) },
                        modifier = Modifier.size(40.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) {
                        Text(if (channel.id in favoriteIds) "★" else "☆")
                    }
                }
            }
        }
    }
}

@Composable
internal fun StableChannelDrawerReplacement(
    channels: List<Channel>,
    query: String,
    selectedChannelId: Long?,
    favoriteIds: Set<Long>,
    epgByChannel: Map<Long, List<EpgProgram>>,
    onQueryChange: (String) -> Unit,
    onSelect: (Long) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onOpenGroups: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Каналы · ${channels.size}") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(min = 300.dp, max = 650.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Поиск канала") },
                    singleLine = true
                )
                StableChannelListReplacement(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    channels = channels,
                    selectedChannelId = selectedChannelId,
                    favoriteIds = favoriteIds,
                    epgByChannel = epgByChannel,
                    onSelect = onSelect,
                    onToggleFavorite = onToggleFavorite
                )
            }
        },
        confirmButton = { OutlinedButton(onClick = onOpenGroups) { Text("Группы") } },
        dismissButton = { Button(onClick = onDismiss) { Text("Закрыть") } }
    )
}
