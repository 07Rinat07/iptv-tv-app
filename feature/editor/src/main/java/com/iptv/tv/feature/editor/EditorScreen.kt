package com.iptv.tv.feature.editor

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelMetadata
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    val saveMetadataRulesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { viewModel.saveMetadataRulesToUri(it.toString()) }
    }
    val openMetadataRulesLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importMetadataRulesFromUri(it.toString()) }
    }
    val filteredChannels = remember(state.channels, state.channelQuery) {
        filterEditorChannels(state.channels, state.channelQuery)
    }
    val visibleLogoCount = remember(filteredChannels) {
        filteredChannels.count { !it.logo.isNullOrBlank() }
    }
    val visibleGroups = remember(filteredChannels) {
        filteredChannels
            .map { it.group?.trim().orEmpty().ifBlank { "Без группы" } }
            .distinct()
            .size
    }
    val metadataRulePreviewCount = remember(
        filteredChannels,
        state.metadataRuleMatcherType,
        state.metadataRuleMatcherInput
    ) {
        metadataRulePreviewCount(
            channels = filteredChannels,
            matcherType = state.metadataRuleMatcherType,
            matcherValue = state.metadataRuleMatcherInput
        )
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
                        Button(onClick = viewModel::selectVisibleChannels, enabled = !state.isLoading) {
                            Text("Выбрать видимые")
                        }
                        Button(onClick = viewModel::selectVisibleChannelsWithoutLogo, enabled = !state.isLoading) {
                            Text("Без логотипа")
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
                                val ext = state.exportFileExtension.ifBlank { "m3u" }
                                if (ext.equals("txt", ignoreCase = true)) {
                                    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                                    saveDocumentLauncher.launch("Tv_list_$stamp.txt")
                                    return@Button
                                }
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
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::saveDraftLogoAsManual,
                            enabled = state.editDraft.channelId != null && !state.isLoading
                        ) {
                            Text("Сохранить как ручной логотип")
                        }
                        Button(
                            onClick = viewModel::clearManualLogo,
                            enabled = state.editDraft.channelId != null && !state.isLoading
                        ) {
                            Text("Очистить ручной логотип")
                        }
                        Button(
                            onClick = viewModel::refreshCurrentPlaylistMetadata,
                            enabled = !state.isRefreshingMetadata
                        ) {
                            Text(if (state.isRefreshingMetadata) "Подбор..." else "Подобрать логотипы")
                        }
                    }
                    state.selectedMetadata?.let { metadata ->
                        ChannelMetadataSummary(metadata = metadata)
                    }
                    OutlinedTextField(
                        value = state.manualCountryInput,
                        onValueChange = viewModel::updateManualCountry,
                        label = { Text("Страна metadata") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.manualLanguageInput,
                        onValueChange = viewModel::updateManualLanguage,
                        label = { Text("Язык metadata") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.manualCategoryInput,
                        onValueChange = viewModel::updateManualCategory,
                        label = { Text("Категория metadata") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = viewModel::saveManualMetadata,
                            enabled = state.editDraft.channelId != null && !state.isLoading
                        ) {
                            Text("Сохранить метаданные")
                        }
                        Button(
                            onClick = viewModel::saveManualMetadataForSelected,
                            enabled = state.selectedChannelIds.isNotEmpty() && !state.isLoading
                        ) {
                            Text("Применить к выбранным")
                        }
                    }
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
                item {
                    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("Сводка видимых каналов", style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Показано=${filteredChannels.size} из ${state.channels.size} | " +
                                    "с логотипами=$visibleLogoCount | групп=$visibleGroups"
                            )
                            Text(
                                "Выбрано=${state.selectedChannelIds.size} | " +
                                    "скрытых в фильтре=${filteredChannels.count { it.isHidden }}"
                            )
                            Button(
                                onClick = viewModel::refreshCurrentPlaylistMetadata,
                                enabled = !state.isRefreshingMetadata
                            ) {
                                Text(if (state.isRefreshingMetadata) "Подбираю логотипы..." else "Подобрать логотипы для плейлиста")
                            }
                            OutlinedTextField(
                                value = state.externalLogoPackUrl,
                                onValueChange = viewModel::updateExternalLogoPackUrl,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("URL внешнего logo pack JSON") },
                                singleLine = true
                            )
                            Button(
                                onClick = viewModel::applyExternalLogoPackUrl,
                                enabled = !state.isRefreshingMetadata && state.externalLogoPackUrl.isNotBlank()
                            ) {
                                Text(if (state.isRefreshingMetadata) "Загружаю..." else "Загрузить и применить logo pack")
                            }
                            OutlinedTextField(
                                value = state.externalLogoPackJson,
                                onValueChange = viewModel::updateExternalLogoPackJson,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Внешний logo pack JSON") },
                                minLines = 3
                            )
                            Button(
                                onClick = viewModel::applyExternalLogoPack,
                                enabled = !state.isRefreshingMetadata && state.externalLogoPackJson.isNotBlank()
                            ) {
                                Text(if (state.isRefreshingMetadata) "Применяю..." else "Применить logo pack")
                            }
                            Text("Конструктор metadata rules", style = MaterialTheme.typography.titleSmall)
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                metadataRuleMatcherTypes.forEach { matcherType ->
                                    Button(
                                        onClick = { viewModel.updateMetadataRuleMatcherType(matcherType) },
                                        enabled = state.metadataRuleMatcherType != matcherType
                                    ) {
                                        Text(matcherType.toMetadataRuleMatcherLabel())
                                    }
                                }
                            }
                            OutlinedTextField(
                                value = state.metadataRuleMatcherInput,
                                onValueChange = viewModel::updateMetadataRuleMatcherInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Условие совпадения") },
                                singleLine = true
                            )
                            Text("Совпадений среди видимых каналов: $metadataRulePreviewCount")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedTextField(
                                    value = state.metadataRuleCountryInput,
                                    onValueChange = viewModel::updateMetadataRuleCountryInput,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Страна") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.metadataRuleLanguageInput,
                                    onValueChange = viewModel::updateMetadataRuleLanguageInput,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Язык") },
                                    singleLine = true
                                )
                                OutlinedTextField(
                                    value = state.metadataRuleCategoryInput,
                                    onValueChange = viewModel::updateMetadataRuleCategoryInput,
                                    modifier = Modifier.weight(1f),
                                    label = { Text("Категория") },
                                    singleLine = true
                                )
                            }
                            Button(
                                onClick = viewModel::appendMetadataRuleFromBuilder,
                                enabled = state.metadataRuleMatcherInput.isNotBlank() &&
                                    listOf(
                                        state.metadataRuleCountryInput,
                                        state.metadataRuleLanguageInput,
                                        state.metadataRuleCategoryInput
                                    ).any { it.isNotBlank() }
                            ) {
                                Text("Добавить rule")
                            }
                            OutlinedTextField(
                                value = state.metadataRulesInput,
                                onValueChange = viewModel::updateMetadataRulesInput,
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Metadata rules") },
                                minLines = 3
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = viewModel::applyMetadataRulesToSelectedOrVisible,
                                    enabled = !state.isRefreshingMetadata && state.metadataRulesInput.isNotBlank()
                                ) {
                                    Text(if (state.isRefreshingMetadata) "Применяю..." else "Применить metadata rules")
                                }
                                Button(
                                    onClick = viewModel::saveMetadataRulesToStorage,
                                    enabled = !state.isLoading && state.metadataRulesInput.isNotBlank()
                                ) {
                                    Text("Сохранить rules")
                                }
                                Button(
                                    onClick = {
                                        val playlistName = currentPlaylist
                                            ?.name
                                            ?.replace(Regex("[\\\\/:*?\"<>|]"), "_")
                                            ?.replace(Regex("\\s+"), "_")
                                            ?.take(40)
                                            ?.ifBlank { "metadata_rules" }
                                            ?: "metadata_rules"
                                        saveMetadataRulesLauncher.launch("$playlistName-metadata-rules.txt")
                                    },
                                    enabled = !state.isLoading && state.metadataRulesInput.isNotBlank()
                                ) {
                                    Text("Сохранить rules как...")
                                }
                                Button(
                                    onClick = { openMetadataRulesLauncher.launch(arrayOf("text/*", "application/octet-stream")) },
                                    enabled = !state.isLoading
                                ) {
                                    Text("Импорт rules")
                                }
                            }
                        }
                    }
                }
                items(filteredChannels, key = { it.id }) { channel ->
                    val selected = channel.id in state.selectedChannelIds
                    Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            val isFavorite = state.favoriteChannelIds.contains(channel.id)
                            ChannelTitleWithLogo(channel = channel, selected = selected)
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

@Composable
private fun ChannelMetadataSummary(metadata: ChannelMetadata) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Text("Метаданные выбранного канала", style = MaterialTheme.typography.titleSmall)
        Text("Источник: ${metadata.metadataSource.toMetadataSourceLabel()}")
        Text("Нормализованное имя: ${metadata.normalizedName.orEmpty().ifBlank { "-" }}")
        Text(
            "Страна: ${metadata.country ?: "-"} | " +
                "язык: ${metadata.language ?: "-"} | категория: ${metadata.category ?: "-"}"
        )
        Text("Итоговый логотип: ${metadata.resolvedLogoUrl ?: "-"}")
        Text("Ручной логотип: ${metadata.manualLogoUrl ?: "-"}")
        Text(
            "Ручные поля: страна=${metadata.manualCountry ?: "-"} | " +
                "язык=${metadata.manualLanguage ?: "-"} | категория=${metadata.manualCategory ?: "-"}"
        )
    }
}

@Composable
private fun ChannelTitleWithLogo(channel: Channel, selected: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(46.dp), contentAlignment = Alignment.Center) {
            if (!channel.logo.isNullOrBlank()) {
                AsyncImage(
                    model = channel.logo,
                    contentDescription = channel.name,
                    modifier = Modifier.size(42.dp)
                )
            } else {
                Text("—", style = MaterialTheme.typography.titleMedium)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (selected) "[x] ${channel.name}" else "[ ] ${channel.name}",
                style = MaterialTheme.typography.titleSmall
            )
            Text(
                text = "ID=${channel.id} | tvg-id=${channel.tvgId ?: "-"}",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun String?.toMetadataSourceLabel(): String {
    return when (this) {
        "manual" -> "ручной override"
        "manual_metadata" -> "ручные метаданные"
        "playlist" -> "из плейлиста"
        "logo-pack:tvg-id" -> "logo pack по tvg-id"
        "logo-pack:name" -> "logo pack по имени"
        "logo-pack:source" -> "logo pack по источнику"
        "catalog:tvg-id" -> "каталог по tvg-id"
        "catalog:name" -> "каталог по имени"
        "catalog:source" -> "каталог по источнику"
        null, "" -> "-"
        else -> this
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

private fun String.toMetadataRuleMatcherLabel(): String {
    return when (this) {
        METADATA_RULE_MATCH_NAME -> "Имя"
        METADATA_RULE_MATCH_GROUP -> "Группа"
        METADATA_RULE_MATCH_TVG_ID -> "TVG ID"
        METADATA_RULE_MATCH_SOURCE -> "Источник"
        else -> "Любое"
    }
}
