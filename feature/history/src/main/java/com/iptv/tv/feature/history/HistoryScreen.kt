package com.iptv.tv.feature.history

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
fun HistoryScreen(
    onOpenPlayer: ((Long) -> Unit)? = null,
    viewModel: HistoryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
        }

        item {
            Button(
                onClick = {
                    val playlistId = viewModel.selectedPlaylistId()
                    if (playlistId != null) {
                        onOpenPlayer?.invoke(playlistId)
                    }
                },
                enabled = state.selectedHistoryId != null
            ) {
                Text("Повторить выбранный")
            }
        }

        item {
            Button(onClick = viewModel::clearHistory, enabled = state.entries.isNotEmpty()) {
                Text("Очистить историю")
            }
        }

        state.lastError?.let { error ->
            item { Text(text = error, color = MaterialTheme.colorScheme.error) }
        }
        state.lastInfo?.let { info ->
            item { Text(text = info) }
        }

        if (state.entries.isEmpty()) {
            item { Text("История пока пуста") }
        } else {
            items(state.entries, key = { it.history.id }) { entry ->
                val selected = entry.history.id == state.selectedHistoryId
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(entry.history.channelName, style = MaterialTheme.typography.titleMedium)
                        Text("playedAt=${entry.history.playedAt}")
                        Text(
                            if (entry.channel != null) {
                                "Playlist=${entry.channel.playlistId} | url=${entry.channel.streamUrl}"
                            } else {
                                "Канал не найден в текущих плейлистах"
                            }
                        )
                        Button(onClick = { viewModel.selectHistory(entry.history.id) }) {
                            Text(if (selected) "Выбрано" else "Выбрать")
                        }
                    }
                }
            }
        }
    }
}

