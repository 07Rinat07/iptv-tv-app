package com.iptv.tv.feature.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.model.BufferProfile as CoreBufferProfile
import com.iptv.tv.core.model.PlayerType as CorePlayerType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenNetworkTest: (() -> Unit)? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Открыть",
    viewModel: SettingsViewModel = hiltViewModel()
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
            Text(
                text = "Экран разбит на секции. Если не уверены, нажмите \"Рекомендуемые\".",
                style = MaterialTheme.typography.bodyLarge
            )
        }

        item {
            SettingsSectionCard(
                title = "Быстрый старт",
                subtitle = "Безопасные значения для большинства TV Box"
            ) {
                Button(onClick = viewModel::applyRecommendedSettings) {
                    Text("Рекомендуемые настройки")
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "AI-сканер",
                subtitle = "Локальный AI (offline): умный подбор запросов и fallback-стратегий"
            ) {
                Text("Статус: ${if (state.scannerAiEnabled) "Включен" else "Выключен"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.scannerAiEnabled,
                        label = "AI Вкл",
                        onClick = { viewModel.setScannerAiEnabled(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.scannerAiEnabled,
                        label = "AI Выкл",
                        onClick = { viewModel.setScannerAiEnabled(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                Text(
                    text = "Рекомендуется держать включенным: поиск строится по тематике запроса и по нескольким провайдерам.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Прокси для сканера",
                subtitle = "Ручной proxy для GitHub/GitLab/Bitbucket поиска"
            ) {
                Text("Статус: ${if (state.scannerProxyEnabled) "Включен" else "Выключен"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.scannerProxyEnabled,
                        label = "Proxy Вкл",
                        onClick = { viewModel.setScannerProxyEnabled(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.scannerProxyEnabled,
                        label = "Proxy Выкл",
                        onClick = { viewModel.setScannerProxyEnabled(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                OutlinedTextField(
                    value = state.scannerProxyHost,
                    onValueChange = viewModel::updateScannerProxyHost,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy host") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.scannerProxyPort,
                    onValueChange = viewModel::updateScannerProxyPort,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy port") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.scannerProxyUsername,
                    onValueChange = viewModel::updateScannerProxyUsername,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy user (опционально)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.scannerProxyPassword,
                    onValueChange = viewModel::updateScannerProxyPassword,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Proxy pass (опционально)") },
                    singleLine = true
                )
                Button(onClick = viewModel::saveScannerProxySettings) {
                    Text("Сохранить прокси")
                }
            }
        }

        onOpenNetworkTest?.let { openNetworkTest ->
            item {
                SettingsSectionCard(
                    title = "Сетевой тест сканера",
                    subtitle = "One-click проверка DNS/API/Web и текущего прокси"
                ) {
                    Button(onClick = openNetworkTest) {
                        Text("Открыть сетевой тест")
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Плеер",
                subtitle = "Выбор проигрывателя по умолчанию"
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.defaultPlayer == CorePlayerType.INTERNAL,
                        label = "Встроенный",
                        onClick = { viewModel.setDefaultPlayer(CorePlayerType.INTERNAL) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.defaultPlayer == CorePlayerType.VLC,
                        label = "VLC",
                        onClick = { viewModel.setDefaultPlayer(CorePlayerType.VLC) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                Text(
                    text = "Текущий: ${state.defaultPlayer.toUiLabel()}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Буферизация",
                subtitle = "Стандартный профиль подходит в большинстве случаев"
            ) {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.bufferProfile == CoreBufferProfile.MINIMAL,
                        label = "Минимальный",
                        onClick = { viewModel.setBufferProfile(CoreBufferProfile.MINIMAL) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.bufferProfile == CoreBufferProfile.STANDARD,
                        label = "Стандарт",
                        onClick = { viewModel.setBufferProfile(CoreBufferProfile.STANDARD) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.bufferProfile == CoreBufferProfile.HIGH,
                        label = "Повышенный",
                        onClick = { viewModel.setBufferProfile(CoreBufferProfile.HIGH) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.bufferProfile == CoreBufferProfile.MANUAL,
                        label = "Ручной",
                        onClick = { viewModel.setBufferProfile(CoreBufferProfile.MANUAL) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }

                Text(
                    text = "Текущий профиль: ${state.bufferProfile.toUiLabel()}",
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = state.manualStartMs,
                    onValueChange = viewModel::updateManualStart,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Стартовый буфер (мс)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.manualRebufferMs,
                    onValueChange = viewModel::updateManualRebuffer,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Подкачка после rebuffer (мс)") },
                    singleLine = true
                )
                OutlinedTextField(
                    value = state.manualMaxMs,
                    onValueChange = viewModel::updateManualMax,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Максимальный буфер (мс)") },
                    singleLine = true
                )
                Button(onClick = viewModel::saveManualBuffer, enabled = !state.isSaving) {
                    Text(if (state.isSaving) "Сохранение..." else "Сохранить ручной буфер")
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Engine Stream",
                subtitle = "Используется для torrent/ace потоков"
            ) {
                OutlinedTextField(
                    value = state.engineEndpoint,
                    onValueChange = viewModel::updateEngineEndpoint,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Endpoint движка") },
                    singleLine = true
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::saveEngineEndpoint,
                        modifier = Modifier.fillMaxWidth(0.48f)
                    ) {
                        Text("Сохранить")
                    }
                    OutlinedButton(
                        onClick = viewModel::resetEngineEndpoint,
                        modifier = Modifier.fillMaxWidth(0.48f)
                    ) {
                        Text("Сбросить")
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Сеть и безопасность",
                subtitle = "Рекомендуется: Tor выключен, только HTTPS"
            ) {
                Text("Tor: ${if (state.torEnabled) "Включен" else "Выключен"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.torEnabled,
                        label = "Tor Вкл",
                        onClick = { viewModel.setTorEnabled(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.torEnabled,
                        label = "Tor Выкл",
                        onClick = { viewModel.setTorEnabled(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }

                Text("Импорт URL: ${if (state.allowInsecureUrls) "HTTP и HTTPS" else "Только HTTPS"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = !state.allowInsecureUrls,
                        label = "Только HTTPS",
                        onClick = { viewModel.setAllowInsecureUrls(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.allowInsecureUrls,
                        label = "Разрешить HTTP",
                        onClick = { viewModel.setAllowInsecureUrls(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Загрузки",
                subtitle = "Ограничения сети и количества задач"
            ) {
                Text("Сеть: ${if (state.downloadsWifiOnly) "Только Wi-Fi/Ethernet" else "Любая"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.downloadsWifiOnly,
                        label = "Только Wi-Fi/Ethernet",
                        onClick = { viewModel.setDownloadsWifiOnly(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.downloadsWifiOnly,
                        label = "Любая сеть",
                        onClick = { viewModel.setDownloadsWifiOnly(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }

                OutlinedTextField(
                    value = state.maxParallelDownloads,
                    onValueChange = viewModel::updateMaxParallelDownloads,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Макс. параллельных загрузок (1..5)") },
                    singleLine = true
                )
                Button(onClick = viewModel::saveMaxParallelDownloads) {
                    Text("Сохранить лимит")
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Юридическое подтверждение",
                subtitle = "Используйте только контент, на который у вас есть права"
            ) {
                Text(
                    text = if (state.legalAccepted) "Статус: подтверждено" else "Статус: не подтверждено"
                )
                if (!state.legalAccepted) {
                    Button(onClick = viewModel::acceptLegal) {
                        Text("Подтвердить правила")
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

@Composable
private fun SettingsSectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Text(title, style = MaterialTheme.typography.titleMedium)
                subtitle?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                content()
            }
        )
    }
}

@Composable
private fun SelectionButton(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (selected) {
        Button(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) {
            Text(label)
        }
    }
}

private fun CorePlayerType.toUiLabel(): String {
    return when (this) {
        CorePlayerType.INTERNAL -> "Встроенный"
        CorePlayerType.VLC -> "VLC"
    }
}

private fun CoreBufferProfile.toUiLabel(): String {
    return when (this) {
        CoreBufferProfile.MINIMAL -> "Минимальный"
        CoreBufferProfile.STANDARD -> "Стандартный"
        CoreBufferProfile.HIGH -> "Повышенный"
        CoreBufferProfile.MANUAL -> "Ручной"
    }
}
