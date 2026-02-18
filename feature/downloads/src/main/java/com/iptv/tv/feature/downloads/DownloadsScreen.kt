package com.iptv.tv.feature.downloads

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import com.iptv.tv.core.designsystem.theme.tvFocusOutline

@Composable
fun DownloadsScreen(
    viewModel: DownloadsViewModel = hiltViewModel()
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
            OutlinedTextField(
                value = state.sourceInput,
                onValueChange = viewModel::updateSourceInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("magnet:/acestream:/ace:/.torrent") },
                minLines = 2
            )
        }

        item {
            OutlinedTextField(
                value = state.maxConcurrentInput,
                onValueChange = viewModel::updateMaxConcurrentInput,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Параллельных задач") },
                singleLine = true
            )
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = viewModel::enqueue) {
                    Text("Добавить в очередь")
                }
                Button(onClick = viewModel::processQueueNow, enabled = !state.isBusy) {
                    Text(if (state.isBusy) "Обработка..." else "Обработать сейчас")
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

        if (state.tasks.isEmpty()) {
            item {
                Text("Очередь загрузок пуста")
            }
        } else {
            items(state.tasks, key = { it.id }) { task ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("Task #${task.id} | ${task.status}")
                        Text("Progress: ${task.progress}%")
                        Text("Source: ${task.source}")
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = { viewModel.pause(task.id) },
                                enabled = viewModel.canPause(task.status)
                            ) {
                                Text("Пауза")
                            }
                            Button(
                                onClick = { viewModel.resume(task.id) },
                                enabled = viewModel.canResume(task.status)
                            ) {
                                Text("Resume")
                            }
                            Button(
                                onClick = { viewModel.cancel(task.id) },
                                enabled = viewModel.canCancel(task.status)
                            ) {
                                Text("Отменить")
                            }
                            Button(onClick = { viewModel.remove(task.id) }) {
                                Text("Удалить")
                            }
                        }
                    }
                }
            }
        }
    }
}
