package com.iptv.tv.feature.diagnostics

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun DiagnosticsScreen(
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "На главную",
    viewModel: DiagnosticsViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { viewModel.exportLogsToUri(it.toString()) }
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
            Text("Сеть: ${state.networkSummary}")
            Text("Runtime: ${state.runtimeSummary}")
            Text("Tor режим: ${if (state.torEnabled) "включен" else "выключен"}")
            Text("Engine: connected=${state.engineConnected}, peers=${state.enginePeers}, speed=${state.engineSpeedKbps} kbps")
            Text("Engine message: ${state.engineMessage}")
            Text("Player avg startup: ${state.playerStartupAvgMs} ms | errors=${state.playerErrorCount} | rebuffer=${state.playerRebufferCount}")
        }

        item {
            OutlinedTextField(
                value = state.engineEndpoint,
                onValueChange = viewModel::updateEngineEndpoint,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Engine endpoint") },
                singleLine = true
            )
        }
        item {
            OutlinedTextField(
                value = state.torrentDescriptor,
                onValueChange = viewModel::updateTorrentDescriptor,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("magnet:/acestream:/ace:/.torrent") },
                minLines = 2
            )
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::connectEngine, enabled = !state.isBusy) {
                    Text(if (state.isBusy) "Подключение..." else "Подключить Engine")
                }
                Button(onClick = viewModel::refreshEngineStatus) {
                    Text("Обновить статус движка")
                }
                Button(onClick = viewModel::resolveTorrentDescriptor, enabled = !state.isBusy) {
                    Text("Resolve torrent descriptor")
                }
                Button(onClick = viewModel::refreshNetworkStatus) {
                    Text("Обновить статус сети")
                }
                Button(onClick = viewModel::refreshRuntimeSummary) {
                    Text("Обновить runtime метрики")
                }
                Button(onClick = viewModel::exportLogsToFile) {
                    Text("Экспорт логов")
                }
                Button(
                    onClick = {
                        saveDocumentLauncher.launch("myscanerIPTV-logs-${System.currentTimeMillis()}.txt")
                    }
                ) {
                    Text("Экспорт логов как...")
                }
            }
        }

        state.resolvedStreamUrl?.let { resolved ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Resolved stream URL", style = MaterialTheme.typography.titleMedium)
                        Text(resolved)
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
        state.exportedLogPath?.let { path ->
            item {
                Text("Файл логов: $path")
            }
        }

        item {
            Text("Последние логи", style = MaterialTheme.typography.titleMedium)
        }
        if (state.logs.isEmpty()) {
            item {
                Text("Логи пока отсутствуют")
            }
        } else {
            items(state.logs.take(80), key = { it.id }) { log ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${log.status} | playlist=${log.playlistId ?: "-"}")
                        Text(log.message)
                        Text("ts=${log.createdAt}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        onPrimaryAction?.let { action ->
            item {
                Button(onClick = action) {
                    Text(primaryLabel)
                }
            }
        }
    }
}

