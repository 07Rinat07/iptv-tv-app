package com.iptv.tv.feature.playlists

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.designsystem.theme.tvBringIntoViewOnFocus
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.isSystemVirtualPlaylistId
import java.util.Locale

const val TAG_PLAYLISTS_LIST = "playlists_list"
const val TAG_PLAYLISTS_REFRESH = "playlists_refresh"
const val TAG_PLAYLISTS_LAST_INFO = "playlists_last_info"
const val TAG_PLAYLISTS_LAST_ERROR = "playlists_last_error"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaylistsScreen(
    onOpenEditor: ((Long) -> Unit)? = null,
    onOpenPlayer: ((Long, Long?) -> Unit)? = null,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val selectedIsVirtualSystem = state.selectedPlaylistId?.let(::isSystemVirtualPlaylistId) == true

    BackHandler(enabled = state.isCatalogOpen) {
        viewModel.handleCatalogBack()
    }

    if (state.isCatalogOpen) {
        state.catalog?.let { catalog ->
            PlaylistCatalogContent(
                snapshot = catalog,
                onBack = { viewModel.handleCatalogBack() },
                onEntryFocused = viewModel::focusCatalogNode,
                onEnter = viewModel::enterCatalogNode,
                onOpenChannel = { channelId ->
                    onOpenPlayer?.invoke(catalog.playlistId, channelId)
                }
            )
            return
        }
    }

    val totalChannels = state.playlists
        .filterNot { playlist -> isSystemVirtualPlaylistId(playlist.id) }
        .sumOf { it.channelCount }
    var showDetails by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var sortModeName by rememberSaveable { mutableStateOf(PlaylistSortMode.CURRENT_FIRST.name) }
    val sortMode = PlaylistSortMode.entries.firstOrNull { it.name == sortModeName }
        ?: PlaylistSortMode.CURRENT_FIRST
    val locale = Locale.getDefault()
    val visiblePlaylists = remember(
        state.playlists,
        state.selectedPlaylistId,
        query,
        sortMode,
        locale
    ) {
        filterAndSortPlaylists(
            playlists = state.playlists,
            selectedPlaylistId = state.selectedPlaylistId,
            query = query,
            sortMode = sortMode,
            locale = locale
        )
    }

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
                        virtualSystemPlaylistDescription(it.id)?.let { description ->
                            Text(description, style = MaterialTheme.typography.bodySmall)
                        }
                        if (showDetails) {
                            Text(
                                "Источник: ${it.source}",
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Последняя синхронизация: ${formatSyncTime(it.lastSyncedAt)}",
                                style = MaterialTheme.typography.bodySmall
                            )
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
                modifier = Modifier.tvBringIntoViewOnFocus(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::refreshSelectedPlaylist,
                    enabled = !selectedIsVirtualSystem && !state.isRefreshing && !state.isDeleting,
                    modifier = Modifier.testTag(TAG_PLAYLISTS_REFRESH)
                ) {
                    Text(if (state.isRefreshing) "Обновление..." else "Обновить сейчас")
                }
                OutlinedButton(
                    onClick = viewModel::deleteSelectedPlaylist,
                    enabled = !selectedIsVirtualSystem && !state.isRefreshing && !state.isDeleting
                ) {
                    Text(if (state.isDeleting) "Удаление..." else "Удалить выбранный плейлист")
                }
                val selectedPlaylistId = state.selectedPlaylistId
                if (selectedPlaylistId != null) {
                    OutlinedButton(
                        onClick = viewModel::openSelectedCatalog,
                        enabled = !state.isLoadingCatalog
                    ) {
                        Text(if (state.isLoadingCatalog) "Каталог загружается..." else "Открыть каталог")
                    }
                    if (!isSystemVirtualPlaylistId(selectedPlaylistId)) {
                        onOpenEditor?.let { openEditor ->
                            OutlinedButton(onClick = { openEditor(selectedPlaylistId) }) {
                                Text("Открыть редактор")
                            }
                        }
                    }
                    onOpenPlayer?.let { openPlayer ->
                        OutlinedButton(onClick = { openPlayer(selectedPlaylistId, null) }) {
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

        if (state.playlists.isNotEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Поиск плейлистов") },
                            supportingText = {
                                Text("Название, источник или тип · найдено ${visiblePlaylists.size}")
                            },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            PlaylistSortMode.entries.forEach { mode ->
                                FilterChip(
                                    selected = mode == sortMode,
                                    onClick = { sortModeName = mode.name },
                                    label = { Text(mode.label) }
                                )
                            }
                        }
                    }
                }
            }
        }

        when {
            state.playlists.isEmpty() -> {
                item {
                    Text("Плейлистов пока нет. Импортируйте список на экране Импорт.")
                }
            }

            visiblePlaylists.isEmpty() -> {
                item {
                    Text("По заданному запросу плейлисты не найдены")
                }
            }

            else -> {
                items(visiblePlaylists, key = { it.id }) { playlist ->
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
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${sourceTypeLabel(playlist.sourceType.name)} · ${playlist.channelCount} каналов",
                                style = MaterialTheme.typography.bodySmall
                            )
                            if (isSystemVirtualPlaylistId(playlist.id)) {
                                Text(
                                    "Виртуальный системный список · физический плейлист не создаётся",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (showDetails) {
                                Text(
                                    "Источник: ${playlist.source}",
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "Обновлено: ${formatSyncTime(playlist.lastSyncedAt)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { viewModel.selectPlaylist(playlist.id) }) {
                                    Text(if (selected) "Выбрано" else "Выбрать")
                                }
                                if (!isSystemVirtualPlaylistId(playlist.id)) {
                                    onOpenEditor?.let { openEditor ->
                                        OutlinedButton(onClick = { openEditor(playlist.id) }) {
                                            Text("Редактировать")
                                        }
                                    }
                                }
                                onOpenPlayer?.let { openPlayer ->
                                    OutlinedButton(onClick = { openPlayer(playlist.id, null) }) {
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
}

private fun virtualSystemPlaylistDescription(playlistId: Long): String? {
    return when (playlistId) {
        VIRTUAL_ALL_CHANNELS_PLAYLIST_ID ->
            "Виртуальный системный список: объединяет каналы всех исходных плейлистов"
        VIRTUAL_FAVORITES_PLAYLIST_ID ->
            "Виртуальный системный список: обновляется автоматически из Избранного"
        else -> null
    }
}

private enum class PlaylistSortMode(val label: String) {
    CURRENT_FIRST("Текущий сначала"),
    NAME("По имени"),
    RECENT("Недавно обновлённые"),
    CHANNELS("Больше каналов")
}

private fun filterAndSortPlaylists(
    playlists: List<Playlist>,
    selectedPlaylistId: Long?,
    query: String,
    sortMode: PlaylistSortMode,
    locale: Locale
): List<Playlist> {
    val normalizedQuery = query.trim().lowercase(locale)
    val filtered = if (normalizedQuery.isBlank()) {
        playlists
    } else {
        playlists.filter { playlist ->
            playlist.name.lowercase(locale).contains(normalizedQuery) ||
                playlist.source.lowercase(locale).contains(normalizedQuery) ||
                sourceTypeLabel(playlist.sourceType.name).lowercase(locale).contains(normalizedQuery)
        }
    }

    val nameComparator = compareBy<Playlist> { it.name.lowercase(locale) }
    return when (sortMode) {
        PlaylistSortMode.CURRENT_FIRST -> filtered.sortedWith(
            compareBy<Playlist> { if (it.id == selectedPlaylistId) 0 else 1 }
                .then(nameComparator)
        )

        PlaylistSortMode.NAME -> filtered.sortedWith(nameComparator)
        PlaylistSortMode.RECENT -> filtered.sortedWith(
            compareByDescending<Playlist> { it.lastSyncedAt ?: 0L }
                .then(nameComparator)
        )

        PlaylistSortMode.CHANNELS -> filtered.sortedWith(
            compareByDescending<Playlist> { it.channelCount }
                .then(nameComparator)
        )
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
    val formatter = java.text.SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    return formatter.format(java.util.Date(value))
}
