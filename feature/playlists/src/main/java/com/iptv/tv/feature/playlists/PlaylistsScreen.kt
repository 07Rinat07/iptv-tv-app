package com.iptv.tv.feature.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.PlaylistContentSummary

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
    var showDetails by rememberSaveable { mutableStateOf(false) }
    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .testTag(TAG_PLAYLISTS_LIST),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "${state.playlists.size} плейлистов · $totalChannels каналов",
                style = MaterialTheme.typography.bodyLarge
            )
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
                    Text("Выбранный плейлист", style = MaterialTheme.typography.titleMedium)
                    Text(
                        current?.name ?: "Плейлист не выбран",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    current?.let {
                        Text("${sourceTypeLabel(it.sourceType.name)} · ${it.channelCount} каналов")
                        if (showDetails) {
                            Text(
                                "Источник: ${it.source}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("Последняя синхронизация: ${formatSyncTime(it.lastSyncedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    if (state.isLoadingSummary) {
                        Text("Сводка плейлиста загружается...")
                    }
                    OutlinedButton(onClick = { showDetails = !showDetails }) {
                        Text(if (showDetails) "Скрыть детали" else "Детали")
                    }
                }
            }
        }

        if (showDetails) state.selectedSummary?.let { summary ->
            item {
                PlaylistContentSummaryCard(summary)
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
                            "${sourceTypeLabel(playlist.sourceType.name)} · ${playlist.channelCount} каналов",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (showDetails) {
                            Text(
                                "Источник: ${playlist.source}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text("Обновлено: ${formatSyncTime(playlist.lastSyncedAt)}", style = MaterialTheme.typography.bodySmall)
                        }
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

@Composable
private fun PlaylistContentSummaryCard(summary: PlaylistContentSummary) {
    Card(
        modifier = Modifier.fillMaxWidth().tvFocusOutline(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Содержимое выбранного плейлиста", style = MaterialTheme.typography.titleMedium)
            Text(
                "Источник: ${sourceTypeLabel(summary.sourceType.name)} | ${summary.source}",
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "EPG: ${summary.epgSourceUrl?.takeIf { it.isNotBlank() } ?: "не задан"}",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "Каналы: всего=${summary.totalChannels}, видимых=${summary.visibleChannels}, " +
                    "скрытых=${summary.hiddenChannels}"
            )
            Text(
                "Лого: ${summary.channelsWithLogo}/${summary.totalChannels} | " +
                    "tvg-id: ${summary.channelsWithTvgId}/${summary.totalChannels} | групп=${summary.groupCount}"
            )
            Text(
                "Health: ok=${summary.availableChannels}, unstable=${summary.unstableChannels}, " +
                    "down=${summary.unavailableChannels}, unknown=${summary.unknownHealthChannels}"
            )
            if (summary.topGroups.isNotEmpty()) {
                Text(
                    "Топ групп: ${summary.topGroups.joinToString { "${it.first} (${it.second})" }}",
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text("Примеры каналов", style = MaterialTheme.typography.titleSmall)
            summary.channelPreviews.take(12).forEach { preview ->
                SummaryChannelPreviewRow(preview)
            }
        }
    }
}

@Composable
private fun SummaryChannelPreviewRow(preview: ChannelPreview) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(42.dp), contentAlignment = Alignment.Center) {
            if (!preview.logo.isNullOrBlank()) {
                AsyncImage(
                    model = preview.logo,
                    contentDescription = preview.name,
                    modifier = Modifier.size(38.dp)
                )
            } else {
                Text("—", style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                preview.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${preview.group ?: "Без группы"} | ${preview.health} | скрыт=${if (preview.isHidden) "да" else "нет"}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
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
        "XTREAM" -> "Xtream Codes"
        "STALKER" -> "Stalker Portal"
        "JELLYFIN" -> "Jellyfin"
        "PLEX" -> "Plex"
        "TVHEADEND" -> "Tvheadend"
        "HDHOMERUN" -> "HdHomeRun"
        "CUSTOM" -> "Пользовательский"
        else -> raw
    }
}

private fun formatSyncTime(value: Long?): String {
    if (value == null || value <= 0L) return "нет данных"
    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", java.util.Locale.getDefault())
    return formatter.format(java.util.Date(value))
}
