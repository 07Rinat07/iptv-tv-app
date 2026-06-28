package com.iptv.tv.feature.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn

private enum class DiagnosticsTab(val title: String) {
    OVERVIEW("Обзор"),
    INDEX("Индекс"),
    CRAWLER("Краулер"),
    ENGINE("Движок"),
    LOGS("Логи")
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiagnosticsScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "На главную",
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(DiagnosticsTab.OVERVIEW) }

    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { viewModel.exportLogsToUri(it.toString()) }
    }

    val pickIndexLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.importIndexedJsonl(it.toString()) }
    }

    val visibleLogs = state.logs
        .filter { log ->
            val query = state.logSearchQuery.trim()
            query.isBlank() ||
                log.status.contains(query, ignoreCase = true) ||
                log.message.contains(query, ignoreCase = true) ||
                log.playlistId?.toString()?.contains(query) == true
        }
        .take(120)

    TvScrollableLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text = state.title, style = MaterialTheme.typography.headlineMedium)
                Text(text = state.description, style = MaterialTheme.typography.bodyLarge)
                if (state.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DiagnosticsTab.entries.forEach { tab ->
                        if (tab == selectedTab) {
                            Button(onClick = { selectedTab = tab }) {
                                Text(tab.title)
                            }
                        } else {
                            OutlinedButton(onClick = { selectedTab = tab }) {
                                Text(tab.title)
                            }
                        }
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

        when (selectedTab) {
            DiagnosticsTab.OVERVIEW -> {
                item {
                    SectionCard(title = "Состояние приложения") {
                        Text("Сеть: ${state.networkSummary}")
                        Text("Runtime: ${state.runtimeSummary}")
                        Text("Tor: ${if (state.torEnabled) "включён" else "выключен"}")
                        Text("Плейлистов в индексе: ${state.allPlaylists.size}")
                        Text("Кандидатов после фильтра: ${state.playlists.size}")
                        Text("Кандидатов сканера: ${state.scanResults.size}")
                        Text("Логов загружено: ${state.logs.size}")
                    }
                }
                item {
                    SectionCard(title = "Плеер и движок") {
                        Text("Engine: ${if (state.engineConnected) "подключён" else "не подключён"}")
                        Text("Peers: ${state.enginePeers}, скорость: ${state.engineSpeedKbps} kbps")
                        Text("Сообщение: ${state.engineMessage}")
                        Text("Средний старт плеера: ${state.playerStartupAvgMs} ms")
                        Text("Ошибки плеера: ${state.playerErrorCount}, rebuffer: ${state.playerRebufferCount}")
                    }
                }
                state.assistantNotes?.let { notes ->
                    item {
                        SectionCard(title = "Помощник") {
                            Text(notes)
                        }
                    }
                }
            }

            DiagnosticsTab.INDEX -> {
                item {
                    SectionCard(title = "Индекс плейлистов") {
                        Text("Импортируйте JSONL из `tools/ai/playlist_indexer.py`, затем фильтруйте и добавляйте лучшие URL.")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    pickIndexLauncher.launch(arrayOf("text/*", "application/json", "application/jsonl"))
                                },
                                enabled = !state.isBusy
                            ) {
                                Text("Импорт JSONL")
                            }
                            OutlinedButton(onClick = { viewModel.loadTopPlaylists() }, enabled = state.allPlaylists.isNotEmpty()) {
                                Text("Лучшие 50")
                            }
                            OutlinedButton(onClick = viewModel::clearPlaylistFilters, enabled = state.allPlaylists.isNotEmpty()) {
                                Text("Сбросить фильтры")
                            }
                            OutlinedButton(onClick = viewModel::togglePlaylistOnlyOk, enabled = state.allPlaylists.isNotEmpty()) {
                                Text(if (state.playlistOnlyOk) "Показывать все HTTP" else "Только HTTP 200")
                            }
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = state.playlistSearchQuery,
                            onValueChange = viewModel::updatePlaylistSearchQuery,
                            modifier = Modifier.weight(1f),
                            label = { Text("URL, host или content-type") },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = state.playlistMinEntries,
                            onValueChange = viewModel::updatePlaylistMinEntries,
                            modifier = Modifier.weight(0.45f),
                            label = { Text("EXTINF от") },
                            singleLine = true
                        )
                    }
                }
                item {
                    Text(
                        "Показано: ${state.playlists.take(200).size} из ${state.playlists.size}; всего в индексе: ${state.allPlaylists.size}",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (state.playlists.isEmpty()) {
                    item {
                        Text("Нет плейлистов по текущим фильтрам")
                    }
                } else {
                    items(state.playlists.take(200), key = { it.url }) { playlist ->
                        PlaylistCandidateCard(
                            entry = playlist,
                            onImport = { viewModel.importPlaylistUrl(playlist.url) }
                        )
                    }
                }
                state.lastCandidateValidation?.let { validation ->
                    item {
                        Text("Последняя проверка: $validation", style = MaterialTheme.typography.bodySmall)
                    }
                }
                state.lastImportedUrl?.let { importedUrl ->
                    item {
                        Text("Последний импорт: $importedUrl", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            DiagnosticsTab.CRAWLER -> {
                item {
                    SectionCard(title = "Краулер и помощник") {
                        Text("Поиск запускает встроенный scanner repository. Для большого офлайн-индекса используйте CLI из `tools/ai`.")
                        OutlinedTextField(
                            value = state.scanQuery,
                            onValueChange = viewModel::updateScanQuery,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Запрос") },
                            placeholder = { Text("iptv, world iptv, russian iptv") },
                            singleLine = true
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = viewModel::runIndexer, enabled = !state.isBusy) {
                                Text(if (state.isBusy) "Идёт поиск..." else "Найти кандидатов")
                            }
                            OutlinedButton(
                                onClick = { viewModel.runAssistant() },
                                enabled = !state.isBusy && (state.scanResults.isNotEmpty() || state.playlists.isNotEmpty())
                            ) {
                                Text("Запустить помощника")
                            }
                        }
                    }
                }
                state.assistantNotes?.let { notes ->
                    item {
                        SectionCard(title = "Рекомендация помощника") {
                            Text(notes)
                        }
                    }
                }
                if (state.scanResults.isEmpty()) {
                    item {
                        Text("Свежих кандидатов пока нет")
                    }
                } else {
                    items(state.scanResults.take(80), key = { it.downloadUrl }) { candidate ->
                        ScanCandidateCard(
                            candidate = candidate,
                            onImport = { viewModel.importPlaylistUrl(candidate.downloadUrl) }
                        )
                    }
                }
            }

            DiagnosticsTab.ENGINE -> {
                item {
                    SectionCard(title = "Ace/Torrent Engine") {
                        OutlinedTextField(
                            value = state.engineEndpoint,
                            onValueChange = viewModel::updateEngineEndpoint,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Endpoint движка") },
                            singleLine = true
                        )
                        Text("Статус: ${if (state.engineConnected) "подключён" else "не подключён"}")
                        Text("Peers: ${state.enginePeers}; скорость: ${state.engineSpeedKbps} kbps")
                        Text("Сообщение: ${state.engineMessage}")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(onClick = viewModel::connectEngine, enabled = !state.isBusy) {
                                Text(if (state.isBusy) "Подключение..." else "Подключить")
                            }
                            OutlinedButton(onClick = viewModel::refreshEngineStatus) {
                                Text("Обновить")
                            }
                        }
                    }
                }
                item {
                    SectionCard(title = "Проверка descriptor") {
                        OutlinedTextField(
                            value = state.torrentDescriptor,
                            onValueChange = viewModel::updateTorrentDescriptor,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("magnet/acestream/ace/.torrent") },
                            minLines = 2
                        )
                        Button(onClick = viewModel::resolveTorrentDescriptor, enabled = !state.isBusy) {
                            Text("Проверить и получить stream URL")
                        }
                    }
                }
                state.resolvedStreamUrl?.let { resolved ->
                    item {
                        SectionCard(title = "Resolved stream URL") {
                            Text(resolved)
                        }
                    }
                }
            }

            DiagnosticsTab.LOGS -> {
                item {
                    SectionCard(title = "Журналы") {
                        Text("Фильтр ищет по статусу, сообщению и playlist id.")
                        OutlinedTextField(
                            value = state.logSearchQuery,
                            onValueChange = viewModel::updateLogSearchQuery,
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text("Фильтр логов") },
                            singleLine = true
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = viewModel::refreshNetworkStatus) {
                                Text("Обновить сеть")
                            }
                            OutlinedButton(onClick = viewModel::refreshRuntimeSummary) {
                                Text("Обновить runtime")
                            }
                            Button(onClick = viewModel::exportLogsToFile) {
                                Text("Экспорт")
                            }
                            OutlinedButton(
                                onClick = {
                                    saveDocumentLauncher.launch("myscanerIPTV-logs-${System.currentTimeMillis()}.txt")
                                }
                            ) {
                                Text("Экспорт как...")
                            }
                        }
                    }
                }
                state.exportedLogPath?.let { path ->
                    item {
                        Text("Файл логов: $path")
                    }
                }
                if (visibleLogs.isEmpty()) {
                    item {
                        Text("Логи по текущему фильтру отсутствуют")
                    }
                } else {
                    items(visibleLogs, key = { it.id }) { log ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("${log.status} | playlist=${log.playlistId ?: "-"}")
                                Text(log.message, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                Text("ts=${log.createdAt}", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        onPrimaryAction?.let { action ->
            item {
                OutlinedButton(onClick = action) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

@Composable
private fun SectionCard(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun PlaylistCandidateCard(
    entry: PlaylistEntry,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(entry.url, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "host=${entry.host ?: "-"} | HTTP=${entry.status ?: "-"} | EXTINF=${entry.m3uEntries} | ${entry.contentType ?: "-"}",
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = onImport) {
                Text("Проверить и добавить")
            }
        }
    }
}

@Composable
private fun ScanCandidateCard(
    candidate: ScanCandidate,
    onImport: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(candidate.name ?: candidate.downloadUrl, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "repo=${candidate.repository ?: "-"} | size=${candidate.sizeBytes ?: 0} | updated=${candidate.updatedAt ?: "-"}",
                style = MaterialTheme.typography.bodySmall
            )
            Text(candidate.downloadUrl, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Button(onClick = onImport) {
                Text("Проверить и добавить")
            }
        }
    }
}
