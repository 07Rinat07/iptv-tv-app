package com.iptv.tv.feature.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel

@Composable
internal fun HomeChannelRail(
    playlistId: Long,
    playlistName: String,
    channels: List<Channel>,
    selectedChannelId: Long?,
    enabled: Boolean,
    listState: LazyListState,
    focusRequester: FocusRequester,
    onZoneFocused: (HomeDashboardFocusZone) -> Unit,
    requestMainFocus: () -> Boolean,
    onWatchChannel: (Long, Long) -> Unit
) {
    val focusIndex = homeChannelRailFocusIndex(channels, selectedChannelId) ?: return

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        tonalElevation = 0.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Каналы рядом", style = MaterialTheme.typography.titleMedium)
                    Text(
                        playlistName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Text(
                    "${channels.size} доступно",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            LazyRow(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.DirectionUp) {
                            requestMainFocus()
                        } else {
                            false
                        }
                    },
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                itemsIndexed(channels, key = { _, channel -> channel.id }) { index, channel ->
                    var modifier = Modifier
                        .width(172.dp)
                        .tvFocusOutline()
                        .onFocusChanged { focusState ->
                            if (focusState.isFocused) {
                                onZoneFocused(HomeDashboardFocusZone.CHANNEL_RAIL)
                            }
                        }
                    if (index == focusIndex) {
                        modifier = modifier.focusRequester(focusRequester)
                    }

                    val label = buildString {
                        append(channel.name)
                        channel.group?.takeIf { it.isNotBlank() }?.let { group ->
                            append(" · ")
                            append(group)
                        }
                    }
                    if (channel.id == selectedChannelId) {
                        Button(
                            onClick = { onWatchChannel(playlistId, channel.id) },
                            enabled = enabled,
                            modifier = modifier
                        ) {
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onWatchChannel(playlistId, channel.id) },
                            enabled = enabled,
                            modifier = modifier
                        ) {
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
