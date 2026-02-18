package com.iptv.tv.feature.favorites

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@Composable
fun FavoritesScreen(
    onOpenPlayer: ((Long) -> Unit)? = null,
    viewModel: FavoritesViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
        }

        item {
            Button(
                onClick = {
                    val channel = state.channels.firstOrNull { it.id == state.selectedChannelId }
                    val playlistId = channel?.playlistId
                    if (playlistId != null) {
                        onOpenPlayer?.invoke(playlistId)
                    }
                },
                enabled = state.selectedChannelId != null
            ) {
                Text("Воспроизвести выбранный")
            }
        }

        item {
            Button(onClick = viewModel::removeSelectedFromFavorites, enabled = state.selectedChannelId != null) {
                Text("Удалить из избранного")
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
                        Text(channel.name, style = MaterialTheme.typography.titleMedium)
                        Text("Group: ${channel.group ?: "-"} | health=${channel.health}")
                        Text("URL: ${channel.streamUrl}")
                        Button(onClick = { viewModel.selectChannel(channel.id) }) {
                            Text(if (selected) "Выбрано" else "Выбрать")
                        }
                    }
                }
            }
        }
    }
}

