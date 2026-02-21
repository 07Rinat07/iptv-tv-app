package com.iptv.tv.feature.playlists

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
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

const val TAG_PLAYLISTS_LIST = "playlists_list"
const val TAG_PLAYLISTS_REFRESH = "playlists_refresh"
const val TAG_PLAYLISTS_LAST_INFO = "playlists_last_info"
const val TAG_PLAYLISTS_LAST_ERROR = "playlists_last_error"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(
    onOpenEditor: ((Long) -> Unit)? = null,
    onOpenPlayer: ((Long) -> Unit)? = null,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val totalChannels = state.playlists.sumOf { it.channelCount }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_PLAYLISTS_LIST),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
            Text("Счетчики: списков=${state.playlists.size} | каналов во всех списках=$totalChannels")
            Text("Совет: выберите плейлист и используйте быстрые кнопки ниже.")
            Text("Управление: пульт (стрелки + OK) и мышь.")
        }

        item {
            val current = state.playlists.firstOrNull { it.id == state.selectedPlaylistId }
            Card(
                modifier = Modifier.fillMaxWidth().tvFocusOutline(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Управление списками", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Всего плейлистов: ${state.playlists.size} | " +
                            "текущий: ${current?.name ?: "не выбран"}"
                    )
                    current?.let {
                        Text("Источник: ${sourceTypeLabel(it.sourceType.name)}")
                        Text("Ссылка/путь: ${it.source}")
                        Text("Каналов: ${it.channelCount} | Последняя синхронизация: ${formatSyncTime(it.lastSyncedAt)}")
                    }
                }
            }
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::refreshSelectedPlaylist,
                    enabled = !state.isRefreshing && !state.isDeleting,
                    modifier = Modifier.testTag(TAG_PLAYLISTS_REFRESH)
                ) {
                    Text(if (state.isRefreshing) "Обновление..." else "Обновить сейчас")
                }
                OutlinedButton(
                    onClick = viewModel::deleteSelectedPlaylist,
                    enabled = !state.isRefreshing && !state.isDeleting
                ) {
                    Text(if (state.isDeleting) "Удаление..." else "Удалить выбранный плейлист")
                }
                val selectedPlaylistId = state.selectedPlaylistId
                if (selectedPlaylistId != null) {
                    onOpenEditor?.let { openEditor ->
                        OutlinedButton(onClick = { openEditor(selectedPlaylistId) }) {
                            Text("Открыть редактор")
                        }
                    }
                    onOpenPlayer?.let { openPlayer ->
                        OutlinedButton(onClick = { openPlayer(selectedPlaylistId) }) {
                            Text("Открыть плеер")
                        }
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Text(
                    text = error,
                    modifier = Modifier.testTag(TAG_PLAYLISTS_LAST_ERROR),
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        state.lastInfo?.let { info ->
            item {
                Text(
                    text = info,
                    modifier = Modifier.testTag(TAG_PLAYLISTS_LAST_INFO)
                )
            }
        }

        if (state.playlists.isEmpty()) {
            item {
                Text("Плейлистов пока нет. Импортируйте список на экране Импорт.")
            }
        } else {
            items(state.playlists, key = { it.id }) { playlist ->
                val selected = state.selectedPlaylistId == playlist.id
                Card(
                    modifier = Modifier.fillMaxWidth().tvFocusOutline(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (selected) "${playlist.name} (текущий)" else playlist.name,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            "ID: ${playlist.id} | тип: ${sourceTypeLabel(playlist.sourceType.name)} | " +
                                "кастомный=${if (playlist.isCustom) "да" else "нет"} | каналов=${playlist.channelCount}"
                        )
                        Text("Источник: ${playlist.source}")
                        Text("Последняя синхронизация: ${formatSyncTime(playlist.lastSyncedAt)}")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = { viewModel.selectPlaylist(playlist.id) }) {
                                Text(if (selected) "Выбрано" else "Выбрать")
                            }
                            onOpenEditor?.let { openEditor ->
                                OutlinedButton(onClick = { openEditor(playlist.id) }) {
                                    Text("Редактировать")
                                }
                            }
                            onOpenPlayer?.let { openPlayer ->
                                OutlinedButton(onClick = { openPlayer(playlist.id) }) {
                                    Text("Воспроизвести")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun sourceTypeLabel(raw: String): String {
    return when (raw.uppercase()) {
        "URL" -> "URL"
        "TEXT" -> "Текст"
        "FILE" -> "Локальный файл"
        "GITHUB" -> "GitHub"
        "GITLAB" -> "GitLab"
        "BITBUCKET" -> "Bitbucket"
        "CUSTOM" -> "Пользовательский"
        else -> raw
    }
}

private fun formatSyncTime(value: Long?): String {
    if (value == null || value <= 0L) return "нет данных"
    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(value))
}

