package com.iptv.tv.feature.player

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
import androidx.compose.material3.Card
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
    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                "Каналы рядом",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleSmall
            )
            OutlinedButton(
                onClick = { scope.launch { listState.animateScrollToItem(0) } }
            ) { Text("В начало") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(
                            StableChannelNavigation.pageTargetIndex(
                                currentIndex = listState.firstVisibleItemIndex,
                                itemCount = channels.size,
                                pageSize = 3,
                                direction = -1
                            )
                        )
                    }
                }
            ) { Text("◀") }
            OutlinedButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(
                            StableChannelNavigation.pageTargetIndex(
                                currentIndex = listState.firstVisibleItemIndex,
                                itemCount = channels.size,
                                pageSize = 3,
                                direction = 1
                            )
                        )
                    }
                }
            ) { Text("▶") }
        }
        LazyRow(
            modifier = Modifier.fillMaxWidth().focusGroup(),
            state = listState,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(channels, key = { it.id }) { channel ->
                val current = stableCurrentProgram(
                    epgByChannel[channel.id].orEmpty(),
                    System.currentTimeMillis()
                )
                val p2pStatus = if (PlayerP2pDescriptor.detect(channel.streamUrl) != null) {
                    p2pChannelAvailabilityLabel(P2pChannelAvailabilityUiCache.statuses[channel.id])
                } else {
                    null
                }
                Card(
                    modifier = Modifier
                        .width(184.dp)
                        .tvFocusOutline()
                        .clickable { onSelectChannel(channel.id) }
                ) {
                    Column(
                        Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            channel.name,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            p2pStatus ?: current?.let { "${stableTime(it.startEpochMs)} ${it.title}" }
                                ?: "EPG нет",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
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
    Card(modifier = modifier.tvFocusOutline()) {
        Column(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Каналы · ${channels.size}",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium
                )
                OutlinedButton(onClick = onOpenGroups) { Text("Группы") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск") },
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
    Row(modifier) {
        LazyColumn(
            modifier = Modifier
                .weight(1f)
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
                val current = stableCurrentProgram(
                    epgByChannel[channel.id].orEmpty(),
                    System.currentTimeMillis()
                )
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
                    tonalElevation = if (selected) 8.dp else 1.dp,
                    color = if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
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
                            modifier = Modifier.size(38.dp)
                        )
                        Column(
                            Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Text(
                                channel.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) {
                                    FontWeight.Bold
                                } else {
                                    FontWeight.Normal
                                }
                            )
                            Text(
                                p2pStatus ?: current?.let {
                                    "${stableTime(it.startEpochMs)}–${stableTime(it.endEpochMs)} ${it.title}"
                                } ?: "Программа не найдена",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(onClick = { onToggleFavorite(channel.id) }) {
                            Text(if (channel.id in favoriteIds) "★" else "☆")
                        }
                    }
                }
            }
        }
        VerticalScrollControls(state = listState, itemCount = channels.size)
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
