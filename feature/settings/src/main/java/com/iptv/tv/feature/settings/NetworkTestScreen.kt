package com.iptv.tv.feature.settings

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkTestScreen(
    onOpenSettings: (() -> Unit)? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Открыть сканер",
    viewModel: NetworkTestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

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
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Прокси: ${state.proxySummary}", style = MaterialTheme.typography.bodyMedium)
                    Text("Последний запуск: ${state.lastRunAt}", style = MaterialTheme.typography.bodySmall)
                    Text(state.summary, style = MaterialTheme.typography.bodyMedium)
                    if (state.recommendation.isNotBlank()) {
                        Text(
                            text = state.recommendation,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }

        item {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = viewModel::runNetworkTest,
                    enabled = !state.isRunning
                ) {
                    Text(if (state.isRunning) "Проверка..." else "Проверить сеть")
                }
                OutlinedButton(
                    onClick = viewModel::clearResults,
                    enabled = !state.isRunning
                ) {
                    Text("Очистить")
                }
                onOpenSettings?.let { openSettings ->
                    OutlinedButton(onClick = openSettings) {
                        Text("Настройки прокси")
                    }
                }
            }
        }

        if (state.results.isEmpty()) {
            item {
                Text("Результатов пока нет. Запустите сетевой тест.")
            }
        } else {
            items(state.results, key = { "${it.scope}:${it.label}" }) { item ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("${item.label} (${item.scope})", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "DNS: ${if (item.dnsOk) "OK" else "FAIL"} | ${item.dnsDetail}",
                            color = if (item.dnsOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text(
                            text = "HTTP: ${if (item.httpOk) "OK" else "FAIL"} | code=${item.httpCode ?: "-"} | ${item.httpDetail}",
                            color = if (item.httpOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                        )
                        Text("Время: ${item.durationMs} мс", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        state.lastError?.let { error ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
        }

        state.lastInfo?.let { info ->
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = info,
                        modifier = Modifier.padding(12.dp)
                    )
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

