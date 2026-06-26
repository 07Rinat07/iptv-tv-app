package com.iptv.tv.feature.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FavoritesScreen(
    onOpenPlayer: ((Long, Long) -> Unit)? = null,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var showDetails by rememberSaveable { mutableStateOf(false) }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text("Избранных каналов: ${state.channels.size}", style = MaterialTheme.typography.bodyLarge)
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val channel = state.channels.firstOrNull { it.id == state.selectedChannelId }
                        val playlistId = channel?.playlistId
                        val channelId = channel?.id
                        if (playlistId != null && channelId != null) {
                            onOpenPlayer?.invoke(playlistId, channelId)
                        }
                    },
                    enabled = state.selectedChannelId != null
                ) {
                    Text("Воспроизвести")
                }
                OutlinedButton(
                    onClick = viewModel::removeSelectedFromFavorites,
                    enabled = state.selectedChannelId != null
                ) {
                    Text("Удалить")
                }
                OutlinedButton(onClick = { showDetails = !showDetails }) {
                    Text(if (showDetails) "Скрыть детали" else "Детали")
                }
            }
        }

        state.lastError?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        state.lastInfo?.let { info ->
            item { Text(text = info) }
        }

        if (state.channels.isEmpty()) {
            item { Text("Избранных каналов пока нет") }
        } else {
            items(state.channels, key = { it.id }) { channel ->
                val selected = channel.id == state.selectedChannelId
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = channel.name,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${channel.group ?: "Без группы"} | ${channel.health}",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (showDetails) {
                            Text(
                                "URL: ${channel.streamUrl}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.selectChannel(channel.id) }) {
                                Text(if (selected) "Выбрано" else "Выбрать")
                            }
                            if (selected) {
                                OutlinedButton(
                                    onClick = {
                                        val playlistId = channel.playlistId
                                        val channelId = channel.id
                                        onOpenPlayer?.invoke(playlistId, channelId)
                                    },
                                    enabled = onOpenPlayer != null
                                ) {
                                    Text("Играть")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
