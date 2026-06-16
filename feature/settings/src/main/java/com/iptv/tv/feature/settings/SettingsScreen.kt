package com.iptv.tv.feature.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.TvHomeChannelType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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
    val context = LocalContext.current
    val openTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    it,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
            viewModel.setRecordingStorageCustomTree(it.toString())
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
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
                title = "Родительский контроль",
                subtitle = "PIN и скрытие adult-групп/каналов по ключевым словам"
            ) {
                Text(
                    "Статус: ${if (state.parentalEnabled) "Включен" else "Выключен"} | " +
                        "PIN: ${if (state.parentalPinConfigured) "задан" else "не задан"}"
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.parentalEnabled,
                        label = "PIN Вкл",
                        onClick = { viewModel.setParentalEnabled(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.parentalEnabled,
                        label = "PIN Выкл",
                        onClick = { viewModel.setParentalEnabled(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                Text("Adult-фильтр: ${if (state.parentalHideAdultChannels) "скрывать" else "не скрывать"}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.parentalHideAdultChannels,
                        label = "Скрывать adult",
                        onClick = { viewModel.setParentalHideAdultChannels(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.parentalHideAdultChannels,
                        label = "Не скрывать",
                        onClick = { viewModel.setParentalHideAdultChannels(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                if (state.parentalPinConfigured) {
                    OutlinedTextField(
                        value = state.parentalCurrentPin,
                        onValueChange = viewModel::updateParentalCurrentPin,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Текущий PIN") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                }
                OutlinedTextField(
                    value = state.parentalNewPin,
                    onValueChange = viewModel::updateParentalNewPin,
                    modifier = Modifier.fillMaxWidth(),
                    label = {
                        Text(
                            if (state.parentalPinConfigured) {
                                "Новый PIN (опционально)"
                            } else {
                                "Новый PIN"
                            }
                        )
                    },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation()
                )
                OutlinedTextField(
                    value = state.parentalKeywordsText,
                    onValueChange = viewModel::updateParentalKeywords,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Ключевые слова через запятую") },
                    minLines = 2
                )
                Button(onClick = viewModel::saveParentalControl, enabled = !state.isSaving) {
                    Text(if (state.isSaving) "Сохранение..." else "Сохранить родительский контроль")
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

                Text("Папка записей: ${state.recordingStorageLocation.toRecordingStorageLabel()}")
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.recordingStorageLocation == RecordingStorageLocation.INTERNAL,
                        label = "Внутренняя",
                        onClick = { viewModel.setRecordingStorageLocation(RecordingStorageLocation.INTERNAL) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.recordingStorageLocation == RecordingStorageLocation.APP_EXTERNAL,
                        label = "Внешняя app",
                        onClick = { viewModel.setRecordingStorageLocation(RecordingStorageLocation.APP_EXTERNAL) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.recordingStorageLocation == RecordingStorageLocation.CUSTOM_EXTERNAL,
                        label = "Custom папка",
                        onClick = {
                            if (state.recordingCustomTreeUri.isNullOrBlank()) {
                                openTreeLauncher.launch(null)
                            } else {
                                viewModel.setRecordingStorageLocation(RecordingStorageLocation.CUSTOM_EXTERNAL)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    OutlinedButton(
                        onClick = { openTreeLauncher.launch(null) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    ) {
                        Text(
                            if (state.recordingCustomTreeUri.isNullOrBlank()) {
                                "Выбрать папку"
                            } else {
                                "Сменить папку"
                            }
                        )
                    }
                }
                Text(
                    text = "Новые записи сохраняются в выбранную папку; старые записи остаются на месте.",
                    style = MaterialTheme.typography.bodySmall
                )
                state.recordingStorageInfo?.let { info ->
                    Text(
                        text = "Путь: ${info.path}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = "Статус: ${info.toRecordingStorageStatusLabel()} | " +
                            "свободно: ${info.freeBytes.toStorageSizeLabel()}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    if (info.usingFallback) {
                        Text(
                            text = "Внешняя папка недоступна, используется внутренняя папка приложения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    if (state.recordingStorageLocation == RecordingStorageLocation.CUSTOM_EXTERNAL && !info.configured) {
                        Text(
                            text = "Для кастомной папки сначала выберите каталог через системный picker.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
                OutlinedButton(onClick = viewModel::refreshRecordingStorageInfo) {
                    Text("Обновить статус папки")
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Android TV Home",
                subtitle = "Публикация рядов Недавние, Избранное, Watch Next и Записи на главный экран TV Box"
            ) {
                state.tvHomeStates.forEach { rowState ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "${rowState.type.toTvHomeLabel()}: ${
                                if (rowState.enabled) "публиковать" else "не публиковать"
                            }",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "Последняя публикация: ${rowState.lastPublishedAt.toPublishedAtLabel()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            SelectionButton(
                                selected = rowState.enabled,
                                label = "Вкл",
                                onClick = { viewModel.setTvHomeRowEnabled(rowState.type, true) },
                                modifier = Modifier.fillMaxWidth(0.48f)
                            )
                            SelectionButton(
                                selected = !rowState.enabled,
                                label = "Выкл",
                                onClick = { viewModel.setTvHomeRowEnabled(rowState.type, false) },
                                modifier = Modifier.fillMaxWidth(0.48f)
                            )
                        }
                    }
                }
                Button(
                    onClick = viewModel::publishTvHomeNow,
                    enabled = !state.isPublishingTvHome
                ) {
                    Text(if (state.isPublishingTvHome) "Публикация..." else "Опубликовать сейчас")
                }
                Text(
                    text = "Автообновление выполняется worker-ом каждые 6 часов.",
                    style = MaterialTheme.typography.bodySmall
                )
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

private fun TvHomeChannelType.toTvHomeLabel(): String {
    return when (this) {
        TvHomeChannelType.RECENT_CHANNELS -> "Недавние каналы"
        TvHomeChannelType.FAVORITES -> "Избранные каналы"
        TvHomeChannelType.WATCH_NEXT -> "Watch Next"
        TvHomeChannelType.RECORDINGS -> "Записи эфира"
    }
}

private fun RecordingStorageLocation.toRecordingStorageLabel(): String {
    return when (this) {
        RecordingStorageLocation.INTERNAL -> "внутренняя папка приложения"
        RecordingStorageLocation.APP_EXTERNAL -> "внешняя папка приложения"
        RecordingStorageLocation.CUSTOM_EXTERNAL -> "внешняя папка через system picker"
    }
}

private fun com.iptv.tv.core.model.RecordingStorageInfo.toRecordingStorageStatusLabel(): String {
    return when {
        !configured -> "папка не выбрана"
        !exists -> "папка ещё не создана"
        writable -> "доступна для записи"
        else -> "нет доступа на запись"
    }
}

private fun Long.toStorageSizeLabel(): String {
    if (this < 0L) return "неизвестно"
    val gib = this / (1024.0 * 1024.0 * 1024.0)
    if (gib >= 1.0) return String.format(Locale.getDefault(), "%.1f GB", gib)
    val mib = this / (1024.0 * 1024.0)
    return String.format(Locale.getDefault(), "%.0f MB", mib)
}

private fun Long?.toPublishedAtLabel(): String {
    if (this == null || this <= 0L) return "ещё не публиковалось"
    return SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(this))
}
