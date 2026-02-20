package com.iptv.tv.feature.scanner

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.theme.tvFocusOutline
import com.iptv.tv.core.model.ScannerProviderScope
import com.iptv.tv.core.model.ScannerSearchMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScannerScreen(
    onPrimaryAction: (() -> Unit)? = null,
    onImportCandidate: ((downloadUrl: String, playlistName: String) -> Unit)? = null,
    primaryLabel: String = "Мои плейлисты",
    viewModel: ScannerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()
    val repoHint = when (state.selectedProvider) {
        ScannerProviderScope.BITBUCKET -> "Обязательно: workspace или workspace/repo"
        ScannerProviderScope.GITHUB -> "Опционально: owner/repo"
        ScannerProviderScope.GITLAB -> "Опционально: group/project"
        ScannerProviderScope.ALL -> "Опционально: owner/repo или group/project"
    }

    Box(modifier = Modifier.fillMaxSize()) {
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
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text("Быстрый старт", style = MaterialTheme.typography.titleMedium)
                    Text("1) Выберите пресет (например: Русские каналы)")
                    Text("2) Проверьте запрос и источник поиска")
                    Text("3) Нажмите \"Найти и сохранить найденное\"")
                    Text("4) Поиск идет до 5 минут, затем найденное сохранится автоматически")
                    Text("5) Откройте \"Мои плейлисты\"")
                }
            }
        }

        item {
            StatusCard(state = state)
        }

        item {
            Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Готовые поиски", style = MaterialTheme.typography.titleMedium)
                    PresetSelector(
                        presets = state.presets,
                        selectedPresetId = state.selectedPresetId,
                        enabled = !state.isLoading,
                        onSelect = viewModel::applyPreset
                    )
                }
            }
        }

        item {
            OutlinedTextField(
                value = state.query,
                onValueChange = viewModel::updateQuery,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поисковый запрос") },
                placeholder = { Text("iptv, world iptv, russian iptv, movie iptv") },
                supportingText = { Text("Обязательное поле.") },
                singleLine = true
            )
        }

        item {
            OutlinedTextField(
                value = state.keywords,
                onValueChange = viewModel::updateKeywords,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Ключевые слова") },
                placeholder = { Text("movie, series, music, news, sport, мультфильмы, экшен, триллер, ужасы") },
                supportingText = { Text("Опционально. RU/EN, через пробел или запятую.") },
                singleLine = true
            )
        }

        item {
            Text("Источник поиска", style = MaterialTheme.typography.titleSmall)
            ProviderSelector(
                selected = state.selectedProvider,
                enabled = !state.isLoading,
                onSelected = viewModel::updateProvider
            )
        }

        item {
            Text("Режим поиска", style = MaterialTheme.typography.titleSmall)
            SearchModeSelector(
                selected = state.selectedSearchMode,
                enabled = !state.isLoading,
                onSelected = viewModel::updateSearchMode
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(onClick = viewModel::scanAndSaveFound, enabled = !state.isLoading) {
                    Text(if (state.isLoading) "Сканирование..." else "Найти и сохранить найденное (до 5 мин)")
                }
                OutlinedButton(onClick = viewModel::scanOnlyTop10, enabled = !state.isLoading) {
                    Text("Только найти")
                }
                if (state.isLoading) {
                    OutlinedButton(onClick = viewModel::stopSearch) {
                        Text("Остановить и сохранить найденное")
                    }
                }
                OutlinedButton(
                    onClick = viewModel::exportFoundLinksToTxt,
                    enabled = !state.isLoading && (state.progressFoundItems > 0 || state.results.isNotEmpty())
                ) {
                    Text("Экспорт найденных ссылок (.txt)")
                }
            }
            Text(
                "Подсказка: при остановке сканера найденное сохраняется. Во время сохранения будет показан прогресс и текущий источник.",
                style = MaterialTheme.typography.bodySmall
            )
            Text(
                "Управление: пульт (стрелки + OK) и мышь поддерживаются на всех кнопках.",
                style = MaterialTheme.typography.bodySmall
            )
        }

        item {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = viewModel::toggleAdvancedFilters, enabled = !state.isLoading) {
                    Text(if (state.showAdvancedFilters) "Скрыть фильтры" else "Показать фильтры")
                }
                OutlinedButton(onClick = viewModel::resetFilters, enabled = !state.isLoading) {
                    Text("Сбросить фильтры")
                }
                onPrimaryAction?.let { action ->
                    OutlinedButton(onClick = action) {
                        Text(primaryLabel)
                    }
                }
            }
        }

        if (state.showAdvancedFilters) {
            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.repoFilter,
                        onValueChange = viewModel::updateRepoFilter,
                        modifier = Modifier.fillMaxWidth(0.49f),
                        label = { Text("Фильтр repo") },
                        placeholder = { Text("owner/repo") },
                        supportingText = { Text(repoHint) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.pathFilter,
                        onValueChange = viewModel::updatePathFilter,
                        modifier = Modifier.fillMaxWidth(0.49f),
                        label = { Text("Фильтр path") },
                        placeholder = { Text("live/ или sports/") },
                        supportingText = { Text("Опционально.") },
                        singleLine = true
                    )
                }
            }

            item {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = state.updatedDaysBack,
                        onValueChange = viewModel::updateUpdatedDaysBack,
                        modifier = Modifier.fillMaxWidth(0.32f),
                        label = { Text("Дней назад") },
                        placeholder = { Text("7") },
                        supportingText = { Text("Только число.") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.minSizeBytes,
                        onValueChange = viewModel::updateMinSize,
                        modifier = Modifier.fillMaxWidth(0.32f),
                        label = { Text("Min size bytes") },
                        placeholder = { Text("100") },
                        supportingText = { Text("Только число.") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = state.maxSizeBytes,
                        onValueChange = viewModel::updateMaxSize,
                        modifier = Modifier.fillMaxWidth(0.32f),
                        label = { Text("Max size bytes") },
                        placeholder = { Text("5000000") },
                        supportingText = { Text("Только число.") },
                        singleLine = true
                    )
                }
            }
        }

        state.selectedPreview?.let { preview ->
            item {
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("Предпросмотр", style = MaterialTheme.typography.titleMedium)
                    Text(preview.name, style = MaterialTheme.typography.titleSmall)
                    Text("Источник: ${preview.provider} | Репозиторий: ${preview.repository}")
                    Text("Путь: ${preview.path}")
                    Text("URL: ${preview.downloadUrl.ifBlank { "не предоставлен" }}")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { onImportCandidate?.invoke(preview.downloadUrl, preview.name) },
                            enabled = preview.downloadUrl.isNotBlank()
                        ) {
                                Text("Импорт вручную")
                            }
                            OutlinedButton(onClick = { viewModel.selectPreview(null) }) {
                                Text("Скрыть")
                            }
                        }
                    }
                }
            }
        }

        item {
            Text(text = "Найдено: ${state.results.size}", style = MaterialTheme.typography.titleMedium)
            if (state.progressFoundItems > state.results.size) {
                Text(
                    text = "Всего найдено в этой сессии: ${state.progressFoundItems} (на экране показаны первые ${state.results.size})",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            state.exportedLinksPath?.let { path ->
                Text(
                    text = "TXT сохранен: $path",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

            items(state.results, key = { it.id }) { item ->
                Card(modifier = Modifier.fillMaxWidth().tvFocusOutline()) {
                    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(text = item.name, style = MaterialTheme.typography.titleMedium)
                        Text(text = "${item.provider} | ${item.repository}", style = MaterialTheme.typography.bodySmall)
                        Text(text = item.path, style = MaterialTheme.typography.bodySmall)
                        Text(text = "Updated: ${item.updatedAt.ifBlank { "-" }} | size=${item.sizeBytes ?: "-"}")
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(onClick = { viewModel.selectPreview(item) }) {
                                Text("Предпросмотр")
                            }
                            OutlinedButton(
                                onClick = { onImportCandidate?.invoke(item.downloadUrl, item.name) },
                                enabled = item.downloadUrl.isNotBlank()
                            ) {
                                Text("Импорт вручную")
                            }
                        }
                    }
                }
            }
        }

        if (state.isLoading) {
            ScannerLiveOverlay(
                state = state,
                onStop = viewModel::stopSearch,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .zIndex(2f)
            )
        }
    }
}

@Composable
private fun StatusCard(state: ScannerUiState) {
    val colors = when (state.statusType) {
        ScannerStatusType.INFO -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ScannerStatusType.LOADING -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ScannerStatusType.SUCCESS -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ScannerStatusType.ERROR -> CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    }

    Card(
        modifier = Modifier.fillMaxWidth().tvFocusOutline(),
        colors = colors
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(state.statusTitle, style = MaterialTheme.typography.titleMedium)
            Text(state.statusDetails, style = MaterialTheme.typography.bodyMedium)
            val totalSteps = state.progressTotalSteps.coerceAtLeast(0)
            val currentStep = state.progressCurrentStep.coerceAtLeast(0)
            val hasStepProgress = totalSteps > 0
            val progress = if (hasStepProgress) {
                (currentStep.coerceAtMost(totalSteps).toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
            } else {
                0f
            }
            if (state.isLoading) {
                if (hasStepProgress) {
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
            if (hasStepProgress || state.isLoading) {
                Text(
                    text = "Шаг: ${if (hasStepProgress) currentStep.coerceAtMost(totalSteps) else 0}/${if (hasStepProgress) totalSteps else "-"} | найдено: ${state.progressFoundItems}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.progressStageLabel.isNotBlank()) {
                Text(
                    text = "Сейчас: ${state.progressStageLabel}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.progressStageLocation.isNotBlank()) {
                Text(
                    text = "Где: ${state.progressStageLocation}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.isLoading || state.progressElapsedSeconds > 0) {
                Text(
                    text = "Время: ${formatElapsed(state.progressElapsedSeconds)} / лимит ${formatElapsed(state.progressTimeLimitSeconds)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (state.isLoading) {
                val remaining = (state.progressTimeLimitSeconds - state.progressElapsedSeconds).coerceAtLeast(0L)
                Text(
                    text = "Осталось примерно: ${formatElapsed(remaining)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun ScannerLiveOverlay(
    state: ScannerUiState,
    onStop: () -> Unit,
    modifier: Modifier = Modifier
) {
    val totalSteps = state.progressTotalSteps.coerceAtLeast(0)
    val currentStep = state.progressCurrentStep.coerceAtLeast(0)
    val hasStepProgress = totalSteps > 0
    val progress = if (hasStepProgress) {
        (currentStep.coerceAtMost(totalSteps).toFloat() / totalSteps.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }

    Card(
        modifier = modifier.fillMaxWidth(0.52f).tvFocusOutline(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Сканирование...", style = MaterialTheme.typography.titleMedium)
            if (hasStepProgress) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = "Шаг ${if (hasStepProgress) currentStep.coerceAtMost(totalSteps) else 0}/${if (hasStepProgress) totalSteps else "-"} | найдено ${state.progressFoundItems}",
                style = MaterialTheme.typography.bodySmall
            )
            if (state.progressStageLabel.isNotBlank()) {
                Text("Сейчас: ${state.progressStageLabel}", style = MaterialTheme.typography.bodySmall)
            }
            if (state.progressStageLocation.isNotBlank()) {
                Text("Где: ${state.progressStageLocation}", style = MaterialTheme.typography.bodySmall)
            }
            Text("Время: ${formatElapsed(state.progressElapsedSeconds)} / ${formatElapsed(state.progressTimeLimitSeconds)}", style = MaterialTheme.typography.bodySmall)
            val remaining = (state.progressTimeLimitSeconds - state.progressElapsedSeconds).coerceAtLeast(0L)
            Text("Осталось: ${formatElapsed(remaining)}", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = onStop) {
                    Text("Остановить")
                }
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val safe = totalSeconds.coerceAtLeast(0L)
    val hours = safe / 3600
    val minutes = (safe % 3600) / 60
    val seconds = safe % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%02d:%02d".format(minutes, seconds)
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun SearchModeSelector(
    selected: ScannerSearchMode,
    enabled: Boolean,
    onSelected: (ScannerSearchMode) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchModeButton(
            label = "Auto",
            mode = ScannerSearchMode.AUTO,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
        SearchModeButton(
            label = "Direct API",
            mode = ScannerSearchMode.DIRECT_API,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
        SearchModeButton(
            label = "Search Engine",
            mode = ScannerSearchMode.SEARCH_ENGINE,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
    }
}

@Composable
private fun SearchModeButton(
    label: String,
    mode: ScannerSearchMode,
    selected: ScannerSearchMode,
    enabled: Boolean,
    onSelected: (ScannerSearchMode) -> Unit
) {
    if (mode == selected) {
        Button(onClick = { onSelected(mode) }, enabled = enabled) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = { onSelected(mode) }, enabled = enabled) {
            Text(label)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ProviderSelector(
    selected: ScannerProviderScope,
    enabled: Boolean,
    onSelected: (ScannerProviderScope) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ProviderButton(
            label = "Все",
            scope = ScannerProviderScope.ALL,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
        ProviderButton(
            label = "GitHub",
            scope = ScannerProviderScope.GITHUB,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
        ProviderButton(
            label = "GitLab",
            scope = ScannerProviderScope.GITLAB,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
        ProviderButton(
            label = "Bitbucket",
            scope = ScannerProviderScope.BITBUCKET,
            selected = selected,
            enabled = enabled,
            onSelected = onSelected
        )
    }
}

@Composable
private fun ProviderButton(
    label: String,
    scope: ScannerProviderScope,
    selected: ScannerProviderScope,
    enabled: Boolean,
    onSelected: (ScannerProviderScope) -> Unit
) {
    if (scope == selected) {
        Button(onClick = { onSelected(scope) }, enabled = enabled) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = { onSelected(scope) }, enabled = enabled) {
            Text(label)
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun PresetSelector(
    presets: List<ScannerPreset>,
    selectedPresetId: String?,
    enabled: Boolean,
    onSelect: (String) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = 2
    ) {
        presets.forEach { preset ->
            if (preset.id == selectedPresetId) {
                Button(
                    onClick = { onSelect(preset.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(0.49f)
                ) {
                    Text(preset.title)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(preset.id) },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(0.49f)
                ) {
                    Text(preset.title)
                }
            }
        }
    }
}
