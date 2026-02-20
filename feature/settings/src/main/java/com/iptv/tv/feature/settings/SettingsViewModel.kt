package com.iptv.tv.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.BufferProfile
import com.iptv.tv.core.model.PlayerType
import com.iptv.tv.core.model.ScannerProxySettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val title: String = "Настройки",
    val description: String = "Плеер, буфер, Engine Stream и Tor",
    val defaultPlayer: PlayerType = PlayerType.INTERNAL,
    val bufferProfile: BufferProfile = BufferProfile.STANDARD,
    val manualStartMs: String = "12000",
    val manualRebufferMs: String = "2000",
    val manualMaxMs: String = "50000",
    val engineEndpoint: String = "http://127.0.0.1:6878",
    val torEnabled: Boolean = false,
    val legalAccepted: Boolean = false,
    val allowInsecureUrls: Boolean = false,
    val downloadsWifiOnly: Boolean = true,
    val maxParallelDownloads: String = "1",
    val scannerAiEnabled: Boolean = true,
    val scannerProxyEnabled: Boolean = false,
    val scannerProxyHost: String = "",
    val scannerProxyPort: String = "",
    val scannerProxyUsername: String = "",
    val scannerProxyPassword: String = "",
    val isSaving: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        observeSettings()
    }

    fun setDefaultPlayer(playerType: PlayerType) {
        viewModelScope.launch {
            settingsRepository.setDefaultPlayer(playerType)
            _uiState.update { it.copy(lastInfo = "Плеер по умолчанию: $playerType", lastError = null) }
        }
    }

    fun setBufferProfile(profile: BufferProfile) {
        viewModelScope.launch {
            settingsRepository.setBufferProfile(profile)
            _uiState.update { it.copy(lastInfo = "Профиль буфера: $profile", lastError = null) }
        }
    }

    fun updateManualStart(value: String) {
        _uiState.update { it.copy(manualStartMs = value, lastError = null) }
    }

    fun updateManualRebuffer(value: String) {
        _uiState.update { it.copy(manualRebufferMs = value, lastError = null) }
    }

    fun updateManualMax(value: String) {
        _uiState.update { it.copy(manualMaxMs = value, lastError = null) }
    }

    fun updateEngineEndpoint(value: String) {
        _uiState.update { it.copy(engineEndpoint = value, lastError = null) }
    }

    fun saveEngineEndpoint() {
        viewModelScope.launch {
            val endpoint = _uiState.value.engineEndpoint.trim()
            if (endpoint.isBlank()) {
                _uiState.update { it.copy(lastError = "Endpoint движка не может быть пустым") }
                return@launch
            }
            settingsRepository.setEngineEndpoint(endpoint)
            _uiState.update { it.copy(lastInfo = "Endpoint движка сохранен", lastError = null) }
        }
    }

    fun resetEngineEndpoint() {
        viewModelScope.launch {
            settingsRepository.setEngineEndpoint(DEFAULT_ENGINE_ENDPOINT)
            _uiState.update {
                it.copy(
                    engineEndpoint = DEFAULT_ENGINE_ENDPOINT,
                    lastInfo = "Endpoint движка сброшен на стандартный",
                    lastError = null
                )
            }
        }
    }

    fun setTorEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setTorEnabled(enabled)
            _uiState.update {
                it.copy(
                    lastInfo = if (enabled) "Tor-режим включен" else "Tor-режим выключен",
                    lastError = null
                )
            }
        }
    }

    fun setAllowInsecureUrls(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setAllowInsecureUrls(enabled)
            _uiState.update {
                it.copy(
                    lastInfo = if (enabled) "Разрешены HTTP URL (insecure)" else "Разрешены только HTTPS URL",
                    lastError = null
                )
            }
        }
    }

    fun setDownloadsWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setDownloadsWifiOnly(enabled)
            _uiState.update {
                it.copy(
                    lastInfo = if (enabled) "Загрузки только по Wi-Fi/Ethernet" else "Загрузки разрешены по любой сети",
                    lastError = null
                )
            }
        }
    }

    fun updateMaxParallelDownloads(value: String) {
        _uiState.update { it.copy(maxParallelDownloads = value, lastError = null) }
    }

    fun saveMaxParallelDownloads() {
        val value = _uiState.value.maxParallelDownloads.toIntOrNull()
        if (value == null) {
            _uiState.update { it.copy(lastError = "Введите число для параллельных загрузок") }
            return
        }
        viewModelScope.launch {
            settingsRepository.setMaxParallelDownloads(value)
            _uiState.update { it.copy(lastInfo = "Максимум параллельных загрузок обновлён", lastError = null) }
        }
    }

    fun setScannerAiEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScannerAiEnabled(enabled)
            _uiState.update {
                it.copy(
                    scannerAiEnabled = enabled,
                    lastInfo = if (enabled) "AI-помощник сканера включен" else "AI-помощник сканера выключен",
                    lastError = null
                )
            }
        }
    }

    fun setScannerProxyEnabled(enabled: Boolean) {
        _uiState.update { it.copy(scannerProxyEnabled = enabled, lastError = null) }
    }

    fun updateScannerProxyHost(value: String) {
        _uiState.update { it.copy(scannerProxyHost = value, lastError = null) }
    }

    fun updateScannerProxyPort(value: String) {
        _uiState.update { it.copy(scannerProxyPort = value.filter { ch -> ch.isDigit() }, lastError = null) }
    }

    fun updateScannerProxyUsername(value: String) {
        _uiState.update { it.copy(scannerProxyUsername = value, lastError = null) }
    }

    fun updateScannerProxyPassword(value: String) {
        _uiState.update { it.copy(scannerProxyPassword = value, lastError = null) }
    }

    fun saveScannerProxySettings() {
        val state = _uiState.value
        val enabled = state.scannerProxyEnabled
        val host = state.scannerProxyHost.trim()
        val port = state.scannerProxyPort.toIntOrNull()

        if (enabled) {
            if (host.isBlank()) {
                _uiState.update { it.copy(lastError = "Укажите Proxy host") }
                return
            }
            if (port == null || port !in 1..65535) {
                _uiState.update { it.copy(lastError = "Proxy port должен быть от 1 до 65535") }
                return
            }
        }

        viewModelScope.launch {
            settingsRepository.setScannerProxySettings(
                ScannerProxySettings(
                    enabled = enabled,
                    host = host,
                    port = if (enabled) port else null,
                    username = state.scannerProxyUsername.trim(),
                    password = state.scannerProxyPassword
                )
            )
            _uiState.update {
                it.copy(
                    lastInfo = if (enabled) "Прокси сканера сохранен и включен" else "Прокси сканера выключен",
                    lastError = null
                )
            }
        }
    }

    fun acceptLegal() {
        viewModelScope.launch {
            settingsRepository.setLegalAccepted(true)
            _uiState.update { it.copy(lastInfo = "Правила использования подтверждены", lastError = null) }
        }
    }

    fun applyRecommendedSettings() {
        viewModelScope.launch {
            settingsRepository.setDefaultPlayer(PlayerType.INTERNAL)
            settingsRepository.setBufferProfile(BufferProfile.STANDARD)
            settingsRepository.setManualBuffer(
                startMs = DEFAULT_MANUAL_START_MS,
                rebufferMs = DEFAULT_MANUAL_REBUFFER_MS,
                maxMs = DEFAULT_MANUAL_MAX_MS
            )
            settingsRepository.setEngineEndpoint(DEFAULT_ENGINE_ENDPOINT)
            settingsRepository.setTorEnabled(false)
            settingsRepository.setAllowInsecureUrls(false)
            settingsRepository.setDownloadsWifiOnly(true)
            settingsRepository.setMaxParallelDownloads(1)
            settingsRepository.setScannerAiEnabled(true)
            settingsRepository.setScannerProxySettings(
                ScannerProxySettings(
                    enabled = false,
                    host = "",
                    port = null,
                    username = "",
                    password = ""
                )
            )

            _uiState.update {
                it.copy(
                    lastInfo = "Применены рекомендуемые настройки",
                    lastError = null
                )
            }
        }
    }

    fun saveManualBuffer() {
        val state = _uiState.value
        val start = state.manualStartMs.toIntOrNull()
        val rebuffer = state.manualRebufferMs.toIntOrNull()
        val max = state.manualMaxMs.toIntOrNull()

        if (start == null || rebuffer == null || max == null) {
            _uiState.update { it.copy(lastError = "Введите корректные целые значения буфера") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true, lastError = null, lastInfo = null) }
            settingsRepository.setManualBuffer(startMs = start, rebufferMs = rebuffer, maxMs = max)
            _uiState.update { it.copy(isSaving = false, lastInfo = "Ручные параметры буфера сохранены") }
        }
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.observeDefaultPlayer().collect { player ->
                _uiState.update { it.copy(defaultPlayer = player) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeBufferProfile().collect { profile ->
                _uiState.update { it.copy(bufferProfile = profile) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeManualBuffer().collect { manual ->
                _uiState.update {
                    it.copy(
                        manualStartMs = manual.startMs.toString(),
                        manualRebufferMs = manual.rebufferMs.toString(),
                        manualMaxMs = manual.maxMs.toString()
                    )
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeEngineEndpoint().collect { endpoint ->
                _uiState.update { it.copy(engineEndpoint = endpoint) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeTorEnabled().collect { torEnabled ->
                _uiState.update { it.copy(torEnabled = torEnabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeLegalAccepted().collect { accepted ->
                _uiState.update { it.copy(legalAccepted = accepted) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeAllowInsecureUrls().collect { allowed ->
                _uiState.update { it.copy(allowInsecureUrls = allowed) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeDownloadsWifiOnly().collect { enabled ->
                _uiState.update { it.copy(downloadsWifiOnly = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeMaxParallelDownloads().collect { value ->
                _uiState.update { it.copy(maxParallelDownloads = value.toString()) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeScannerAiEnabled().collect { enabled ->
                _uiState.update { it.copy(scannerAiEnabled = enabled) }
            }
        }
        viewModelScope.launch {
            settingsRepository.observeScannerProxySettings().collect { proxy ->
                _uiState.update {
                    it.copy(
                        scannerProxyEnabled = proxy.enabled,
                        scannerProxyHost = proxy.host,
                        scannerProxyPort = proxy.port?.toString().orEmpty(),
                        scannerProxyUsername = proxy.username,
                        scannerProxyPassword = proxy.password
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_ENGINE_ENDPOINT = "http://127.0.0.1:6878"
        const val DEFAULT_MANUAL_START_MS = 12_000
        const val DEFAULT_MANUAL_REBUFFER_MS = 2_000
        const val DEFAULT_MANUAL_MAX_MS = 50_000
    }
}

