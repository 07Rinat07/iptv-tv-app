package com.iptv.tv.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    onPrimaryAction: ((Long) -> Unit)? = null,
    viewModel: EditorViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val currentPlaylist = remember(state.playlists, state.effectivePlaylistId) {
        state.playlists.firstOrNull { it.id == state.effectivePlaylistId }
    }
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("*/*")
    ) { uri ->
        uri?.let { viewModel.saveExportToUri(it.toString()) }
    }
    val filteredChannels = remember(state.channels, state.channelQuery) {
        val query = state.channelQuery.trim().lowercase()
        if (query.isBlank()) {
            state.channels
        } else {
            state.channels.filter { channel ->
                channel.name.lowercase().contains(query) ||
                    channel.group?.lowercase()?.contains(query) == true ||
                    channel.streamUrl.lowercase().contains(query)
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .focusable(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
            Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "Сейчас редактируется: ${currentPlaylist?.name ?: "не выбран"} " +
                    "(ID: ${state.effectivePlaylistId ?: "-"})"
            )
            Text("Отмечено каналов: ${state.selectedChannelIds.size}")
            currentPlaylist?.let { playlist ->
                Text("Источник: ${editorSourceTypeLabel(playlist.sourceType.name)}")
                Text("Ссылка/путь: ${playlist.source}")
                Text("Всего каналов в списке: ${playlist.channelCount}")
            }
            Text("Шаги: выбрать список -> выбрать каналы -> действие -> экспорт/сохранение")
        }

        if (state.playlists.isNotEmpty()) {
            items(state.playlists, key = { it.id }) { playlist ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("${playlist.name} (id=${playlist.id}, custom=${playlist.isCustom})")
                        Text("Источник: ${editorSourceTypeLabel(playlist.sourceType.name)}")
                        Text("Ссылка/путь: ${playlist.source}")
                        Text("Каналов: ${playlist.channelCount}")
                        Button(onClick = { viewModel.selectPlaylist(playlist.id) }) {
                            Text(if (playlist.id == state.effectivePlaylistId) "Редактируется сейчас" else "Редактировать этот плейлист")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Массовые действия", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = viewModel::ensureEditablePlaylist, enabled = !state.isLoading) {
                            Text("Подготовить COW")
                        }
                        Button(onClick = { viewModel.hideSelected(true) }, enabled = !state.isLoading) {
                            Text("Скрыть")
                        }
                        Button(onClick = { viewModel.hideSelected(false) }, enabled = !state.isLoading) {
                            Text("Показать")
                        }
                        Button(onClick = viewModel::deleteSelected, enabled = !state.isLoading) {
                            Text("Удалить")
                        }
                        Button(onClick = viewModel::deleteUnavailable, enabled = !state.isLoading) {
                            Text("Удалить битые")
                        }
                        Button(onClick = viewModel::moveSelectedToTop, enabled = !state.isLoading) {
                            Text("Вверх")
                        }
                        Button(onClick = viewModel::moveSelectedToBottom, enabled = !state.isLoading) {
                            Text("Вниз")
                        }
                        Button(onClick = viewModel::selectAllChannels, enabled = !state.isLoading) {
                            Text("Выбрать все")
                        }
                        Button(onClick = viewModel::clearSelection, enabled = !state.isLoading) {
                            Text("Снять выбор")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Экспорт", style = MaterialTheme.typography.titleMedium)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = viewModel::exportSelectedOrVisibleM3u, enabled = !state.isLoading) {
                            Text("Экспорт M3U")
                        }
                        Button(onClick = viewModel::exportSelectedOrVisibleM3u8, enabled = !state.isLoading) {
                            Text("Экспорт M3U8")
                        }
                        Button(onClick = viewModel::exportAllPlaylistsToTxt, enabled = !state.isLoading) {
                            Text("Экспорт TXT (все списки)")
                        }
                        Button(onClick = viewModel::saveExportToStorage, enabled = !state.isLoading) {
                            Text("Сохранить в память ТВ")
                        }
                        Button(
                            onClick = {
                                val playlistName = state.playlists
                                    .firstOrNull { it.id == state.effectivePlaylistId }
                                    ?.name
                                    ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                    ?.replace(Regex("\\s+"), "_")
                                    ?.take(40)
                                    ?.ifBlank {
                                        if (state.exportFileExtension.equals("txt", ignoreCase = true)) {
                                            "playlists_export"
                                        } else {
                                            "playlist"
                                        }
                                    }
                                    ?: if (state.exportFileExtension.equals("txt", ignoreCase = true)) {
                                        "playlists_export"
                                    } else {
                                        "playlist"
                                    }
                                val ext = state.exportFileExtension.ifBlank { "m3u" }
                                saveDocumentLauncher.launch("$playlistName.$ext")
                            },
                            enabled = !state.isLoading
                        ) {
                            Text("Сохранить как...")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Редактирование канала", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.editDraft.name,
                        onValueChange = viewModel::updateDraftName,
                        label = { Text("Имя канала") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.editDraft.group,
                        onValueChange = viewModel::updateDraftGroup,
                        label = { Text("Группа") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.editDraft.logo,
                        onValueChange = viewModel::updateDraftLogo,
                        label = { Text("Лого URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.editDraft.streamUrl,
                        onValueChange = viewModel::updateDraftStreamUrl,
                        label = { Text("Stream URL") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = viewModel::saveDraft, enabled = !state.isLoading) {
                        Text("Сохранить канал")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Создать пользовательский плейлист", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = state.customPlaylistName,
                        onValueChange = viewModel::updateCustomPlaylistName,
                        label = { Text("Название") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = viewModel::createCustomPlaylistFromSelection, enabled = !state.isLoading) {
                        Text("Создать из выбранных каналов")
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Text(text = error, color = MaterialTheme.colorScheme.error)
            }
        }
        state.lastInfo?.let { info ->
            item {
                Text(text = info)
            }
        }
        state.exportedFilePath?.let { path ->
            item {
                Text("Путь сохранения: $path")
            }
        }

        state.exportPreview?.let { preview ->
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Предпросмотр экспорта", style = MaterialTheme.typography.titleMedium)
                        Text(preview)
                        onPrimaryAction?.let { open ->
                            state.effectivePlaylistId?.let { playlistId ->
                                Button(onClick = { open(playlistId) }) {
                                    Text("Открыть плейлист")
                                }
                            }
                        }
                    }
                }
            }
        }

        if (state.channels.isEmpty()) {
            item {
                Text("Каналы не найдены")
            }
        } else {
            item {
                OutlinedTextField(
                    value = state.channelQuery,
                    onValueChange = viewModel::updateChannelQuery,
                    label = { Text("Поиск по каналам") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }
            if (filteredChannels.isEmpty()) {
                item {
                    Text("Каналы не найдены по текущему фильтру")
                }
            } else {
                items(filteredChannels, key = { it.id }) { channel ->
                    val selected = channel.id in state.selectedChannelIds
                    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isFavorite = state.favoriteChannelIds.contains(channel.id)
                            Text(
                                text = if (selected) "[x] ${channel.name}" else "[ ] ${channel.name}",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text("Group: ${channel.group ?: "-"} | health=${channel.health} | hidden=${channel.isHidden}")
                            channel.logo?.takeIf { it.isNotBlank() }?.let { logo ->
                                Text("Logo: $logo")
                            }
                            Text("URL: ${channel.streamUrl}")
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(onClick = { viewModel.toggleChannelSelection(channel.id) }) {
                                    Text(if (selected) "Снять выбор" else "Выбрать")
                                }
                                Button(onClick = { viewModel.pickChannelForEdit(channel.id) }) {
                                    Text("Редактировать")
                                }
                                Button(onClick = { viewModel.setSingleChannelHidden(channel.id, !channel.isHidden) }) {
                                    Text(if (channel.isHidden) "Показать канал" else "Скрыть канал")
                                }
                                Button(onClick = { viewModel.deleteSingleChannel(channel.id) }) {
                                    Text("Удалить канал")
                                }
                                Button(onClick = { viewModel.toggleChannelFavorite(channel.id) }) {
                                    Text(if (isFavorite) "Убрать из избранного" else "В избранное")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun editorSourceTypeLabel(raw: String): String {
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

