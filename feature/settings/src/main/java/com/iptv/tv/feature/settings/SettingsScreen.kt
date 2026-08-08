package com.iptv.tv.feature.settings

import android.content.Intent
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.iptv.tv.core.designsystem.components.TvScrollableLazyColumn
import com.iptv.tv.core.model.RecordingStorageLocation
import com.iptv.tv.core.model.TvHomeChannelType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.iptv.tv.core.model.AppStartDestination as CoreAppStartDestination
import com.iptv.tv.core.model.BufferProfile as CoreBufferProfile
import com.iptv.tv.core.model.PlayerType as CorePlayerType

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onOpenNetworkTest: (() -> Unit)? = null,
    onOpenStartDestination: ((CoreAppStartDestination) -> Unit)? = null,
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
    var showQuickStart by rememberSaveable { mutableStateOf(true) }
    var showAiScanner by rememberSaveable { mutableStateOf(false) }
    var showScannerProxy by rememberSaveable { mutableStateOf(false) }
    var showNetworkTest by rememberSaveable { mutableStateOf(false) }
    var showPlayerSettings by rememberSaveable { mutableStateOf(true) }
    var showBufferSettings by rememberSaveable { mutableStateOf(true) }
    var showEngineSettings by rememberSaveable { mutableStateOf(false) }
    var showNetworkSecurity by rememberSaveable { mutableStateOf(false) }
    var showProviderAccounts by rememberSaveable { mutableStateOf(false) }
    var showParentalControl by rememberSaveable { mutableStateOf(false) }
    var showDownloads by rememberSaveable { mutableStateOf(false) }
    var showTvHome by rememberSaveable { mutableStateOf(false) }
    var showLegal by rememberSaveable { mutableStateOf(!state.legalAccepted) }

    TvScrollableLazyColumn(
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
                subtitle = "Безопасные значения для большинства TV Box",
                expanded = showQuickStart,
                onToggleExpanded = { showQuickStart = !showQuickStart }
            ) {
                Button(onClick = viewModel::applyRecommendedSettings) {
                    Text("Рекомендуемые настройки")
                }
                Text(
                    text = "Стартовый экран: ${state.appStartDestination.toUiLabel()}",
                    style = MaterialTheme.typography.bodyMedium
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.appStartDestination == CoreAppStartDestination.HOME,
                        label = "Главная",
                        onClick = {
                            if (viewModel.setAppStartDestination(CoreAppStartDestination.HOME)) {
                                onOpenStartDestination?.invoke(CoreAppStartDestination.HOME)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.appStartDestination == CoreAppStartDestination.PLAYER,
                        label = "Плеер",
                        onClick = {
                            if (viewModel.setAppStartDestination(CoreAppStartDestination.PLAYER)) {
                                onOpenStartDestination?.invoke(CoreAppStartDestination.PLAYER)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.appStartDestination == CoreAppStartDestination.SCANNER,
                        label = "Сканер",
                        onClick = {
                            if (viewModel.setAppStartDestination(CoreAppStartDestination.SCANNER)) {
                                onOpenStartDestination?.invoke(CoreAppStartDestination.SCANNER)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.appStartDestination == CoreAppStartDestination.FAVORITES,
                        label = "Избранное",
                        onClick = {
                            if (viewModel.setAppStartDestination(CoreAppStartDestination.FAVORITES)) {
                                onOpenStartDestination?.invoke(CoreAppStartDestination.FAVORITES)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = state.appStartDestination == CoreAppStartDestination.PLAYLISTS,
                        label = "Плейлисты",
                        onClick = {
                            if (viewModel.setAppStartDestination(CoreAppStartDestination.PLAYLISTS)) {
                                onOpenStartDestination?.invoke(CoreAppStartDestination.PLAYLISTS)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                Text(
                    text = "Выбор сохраняется для следующего запуска и сразу открывает выбранный раздел.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "AI-сканер",
                subtitle = "Локальный AI (offline): умный подбор запросов и fallback-стратегий",
                expanded = showAiScanner,
                onToggleExpanded = { showAiScanner = !showAiScanner }
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
                subtitle = "Ручной proxy для GitHub/GitLab/Bitbucket поиска",
                expanded = showScannerProxy,
                onToggleExpanded = { showScannerProxy = !showScannerProxy }
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
                    subtitle = "One-click проверка DNS/API/Web и текущего прокси",
                    expanded = showNetworkTest,
                    onToggleExpanded = { showNetworkTest = !showNetworkTest }
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
                subtitle = "Выбор проигрывателя по умолчанию",
                expanded = showPlayerSettings,
                onToggleExpanded = { showPlayerSettings = !showPlayerSettings }
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
                subtitle = "Стандартный профиль подходит в большинстве случаев",
                expanded = showBufferSettings,
                onToggleExpanded = { showBufferSettings = !showBufferSettings }
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

                if (state.bufferProfile == CoreBufferProfile.MANUAL) {
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
        }

        item {
            SettingsSectionCard(
                title = "Engine Stream",
                subtitle = "Используется для torrent/ace потоков",
                expanded = showEngineSettings,
                onToggleExpanded = { showEngineSettings = !showEngineSettings }
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
                subtitle = "Рекомендуется: Tor выключен, только HTTPS",
                expanded = showNetworkSecurity,
                onToggleExpanded = { showNetworkSecurity = !showNetworkSecurity }
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
                title = "Provider Accounts",
                subtitle = "Фоновая автосинхронизация сохранённых провайдеров",
                expanded = showProviderAccounts,
                onToggleExpanded = { showProviderAccounts = !showProviderAccounts }
            ) {
                Text(
                    "Статус: ${if (state.providerAutoSyncEnabled) "Включена" else "Выключена"} | " +
                        "интервал: ${state.providerAutoSyncIntervalHours} ч"
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.providerAutoSyncEnabled,
                        label = "Авто-sync Вкл",
                        onClick = { viewModel.setProviderAutoSyncEnabled(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.providerAutoSyncEnabled,
                        label = "Авто-sync Выкл",
                        onClick = { viewModel.setProviderAutoSyncEnabled(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(6, 12, 24).forEach { hours ->
                        SelectionButton(
                            selected = state.providerAutoSyncIntervalHours == hours,
                            label = "$hours ч",
                            onClick = { viewModel.setProviderAutoSyncIntervalHours(hours) },
                            modifier = Modifier.fillMaxWidth(0.31f)
                        )
                    }
                }
                Text(
                    text = "Когда автосинхронизация включена, WorkManager сам подтягивает обновления M3U, Xtream, Stalker и других сохранённых провайдеров.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        item {
            SettingsSectionCard(
                title = "Родительский контроль",
                subtitle = "PIN, защищённые профили и экспорт/импорт настроек блокировки",
                expanded = showParentalControl,
                onToggleExpanded = { showParentalControl = !showParentalControl }
            ) {
                val activeProfile = state.parentalProfiles.firstOrNull { it.enabled }
                if (activeProfile != null) {
                    Text(
                        "Активный профиль: ${activeProfile.name} | " +
                            if (activeProfile.lockedSettings) "настройки защищены PIN" else "без блокировки настроек"
                    )
                }
                if (state.parentalSettingsLocked) {
                    OutlinedTextField(
                        value = state.parentalUnlockPin,
                        onValueChange = viewModel::updateParentalUnlockPin,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("PIN для разблокировки") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation()
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(onClick = viewModel::unlockParentalSettings) {
                            Text("Разблокировать")
                        }
                    }
                    Text(
                        text = "Пока профиль активен, изменения настроек и профилей требуют разблокировки.",
                        style = MaterialTheme.typography.bodySmall
                    )
                } else if (activeProfile?.lockedSettings == true) {
                    OutlinedButton(onClick = viewModel::lockParentalSettings) {
                        Text("Снова заблокировать настройки")
                    }
                }
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
                Button(
                    onClick = viewModel::saveParentalControl,
                    enabled = !state.isSaving && !state.parentalSettingsLocked
                ) {
                    Text(if (state.isSaving) "Сохранение..." else "Сохранить родительский контроль")
                }

                Text("Профили защиты")
                OutlinedTextField(
                    value = state.parentalProfileName,
                    onValueChange = viewModel::updateParentalProfileName,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Имя профиля") },
                    singleLine = true
                )
                Text(
                    "При сохранении профиль забирает текущий PIN и список ключевых слов. Потом его можно быстро активировать на другом устройстве через JSON.",
                    style = MaterialTheme.typography.bodySmall
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SelectionButton(
                        selected = state.parentalProfileLockedSettings,
                        label = "Блокировать настройки",
                        onClick = { viewModel.setParentalProfileLockedSettings(true) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                    SelectionButton(
                        selected = !state.parentalProfileLockedSettings,
                        label = "Без блокировки",
                        onClick = { viewModel.setParentalProfileLockedSettings(false) },
                        modifier = Modifier.fillMaxWidth(0.48f)
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = viewModel::saveParentalProfile,
                        enabled = !state.parentalSettingsLocked
                    ) {
                        Text("Сохранить профиль")
                    }
                    OutlinedButton(
                        onClick = viewModel::clearActiveParentalProfile,
                        enabled = activeProfile != null && !state.parentalSettingsLocked
                    ) {
                        Text("Отключить активный")
                    }
                }

                if (state.parentalProfiles.isEmpty()) {
                    Text("Пока нет сохранённых профилей.", style = MaterialTheme.typography.bodySmall)
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.parentalProfiles.forEach { profile ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "${profile.name} | ${if (profile.enabled) "активен" else "сохранён"} | " +
                                            if (profile.lockedSettings) "с блокировкой" else "без блокировки"
                                    )
                                    Text(
                                        "Ключевых слов: ${profile.blockedKeywords.size}",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = { viewModel.activateParentalProfile(profile.id) },
                                            enabled = !profile.enabled && !state.parentalSettingsLocked
                                        ) {
                                            Text("Активировать")
                                        }
                                        OutlinedButton(
                                            onClick = { viewModel.deleteParentalProfile(profile.id) },
                                            enabled = !state.parentalSettingsLocked
                                        ) {
                                            Text("Удалить")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text("Экспорт / импорт JSON")
                OutlinedTextField(
                    value = state.parentalProfilesJson,
                    onValueChange = viewModel::updateParentalProfilesJson,
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("JSON профилей") },
                    minLines = 6
                )
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = viewModel::exportParentalProfiles,
                        enabled = !state.parentalSettingsLocked
                    ) {
                        Text("Подготовить экспорт")
                    }
                    Button(
                        onClick = { viewModel.importParentalProfiles(replaceExisting = false) },
                        enabled = !state.parentalSettingsLocked
                    ) {
                        Text("Импортировать")
                    }
                }
            }
        }

        item {
            SettingsSectionCard(
                title = "Загрузки",
                subtitle = "Ограничения сети и количества задач",
                expanded = showDownloads,
                onToggleExpanded = { showDownloads = !showDownloads }
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
                subtitle = "Публикация рядов Недавние, Избранное, Watch Next и Записи на главный экран TV Box",
                expanded = showTvHome,
                onToggleExpanded = { showTvHome = !showTvHome }
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
                subtitle = "Используйте только контент, на который у вас есть права",
                expanded = showLegal,
                onToggleExpanded = { showLegal = !showLegal }
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
    expanded: Boolean = true,
    onToggleExpanded: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        subtitle?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = if (expanded) 2 else 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    onToggleExpanded?.let { toggle ->
                        OutlinedButton(onClick = toggle) {
                            Text(if (expanded) "Скрыть" else "Открыть")
                        }
                    }
                }
                if (expanded) {
                    content()
                }
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

private fun CoreAppStartDestination.toUiLabel(): String {
    return when (this) {
        CoreAppStartDestination.HOME -> "Главная"
        CoreAppStartDestination.PLAYER -> "Плеер"
        CoreAppStartDestination.SCANNER -> "Сканер"
        CoreAppStartDestination.FAVORITES -> "Избранное"
        CoreAppStartDestination.PLAYLISTS -> "Мои плейлисты"
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
