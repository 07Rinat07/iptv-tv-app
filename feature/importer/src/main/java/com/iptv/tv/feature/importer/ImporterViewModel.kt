package com.iptv.tv.feature.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.ProviderAccountRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.ProviderAccountStatus
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.ProviderAuthType
import com.iptv.tv.core.model.ProviderType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

data class ImporterUiState(
    val title: String = "Импорт",
    val description: String = "Импорт по URL, тексту или локальному файлу",
    val playlistName: String = "Новый плейлист",
    val url: String = "",
    val xtreamBaseUrl: String = "",
    val xtreamUsername: String = "",
    val xtreamPassword: String = "",
    val stalkerPortalUrl: String = "",
    val stalkerMacAddress: String = "",
    val filePathOrUri: String = "",
    val rawText: String = "",
    val isLoading: Boolean = false,
    val lastError: String? = null,
    val providerMessage: String? = null,
    val savedProviders: List<PlaylistProvider> = emptyList(),
    val syncingProviderId: Long? = null,
    val checkingProviderId: Long? = null,
    val lastProviderStatus: ProviderAccountStatus? = null,
    val lastImportReport: PlaylistImportReport? = null,
    val lastContentSummary: PlaylistContentSummary? = null,
    val lastValidationReport: PlaylistValidationReport? = null
)

@HiltViewModel
class ImporterViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val providerAccountRepository: ProviderAccountRepository,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImporterUiState())
    val uiState: StateFlow<ImporterUiState> = _uiState.asStateFlow()

    init {
        observeProviders()
        applyScannerPrefill()
    }

    fun updatePlaylistName(value: String) = _uiState.update { it.copy(playlistName = value) }
    fun updateUrl(value: String) = _uiState.update { it.copy(url = value) }
    fun updateXtreamBaseUrl(value: String) = _uiState.update { it.copy(xtreamBaseUrl = value) }
    fun updateXtreamUsername(value: String) = _uiState.update { it.copy(xtreamUsername = value) }
    fun updateXtreamPassword(value: String) = _uiState.update { it.copy(xtreamPassword = value) }
    fun updateStalkerPortalUrl(value: String) = _uiState.update { it.copy(stalkerPortalUrl = value) }
    fun updateStalkerMacAddress(value: String) = _uiState.update { it.copy(stalkerMacAddress = value) }
    fun updateFilePath(value: String) = _uiState.update { it.copy(filePathOrUri = value) }
    fun updateRawText(value: String) = _uiState.update { it.copy(rawText = value) }

    fun importFromUrl() {
        if (_uiState.value.isLoading) {
            logAsync(status = "import_click_ignored", message = "Import already running (url)")
            return
        }
        val state = _uiState.value
        if (state.url.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите URL") }
            logAsync(status = "import_ui_error", message = "URL is blank")
            return
        }
        executeImport(importKind = "url", source = state.url.trim()) {
            val url = state.url.trim()
            val insecureAllowed = settingsRepository.observeAllowInsecureUrls().first()
            if (!isSecureOrLocalUrl(url) && !insecureAllowed) {
                return@executeImport AppResult.Error(
                    "Безопасный режим: разрешены HTTPS URL. Для HTTP включите настройку 'Разрешить HTTP URL'."
                )
            }
            playlistRepository.importFromUrl(url, state.playlistName.trim())
        }
    }

    fun importFromXtream() {
        if (_uiState.value.isLoading) {
            logAsync(status = "import_click_ignored", message = "Import already running (xtream)")
            return
        }
        val state = _uiState.value
        val baseUrl = state.xtreamBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите URL сервера Xtream") }
            logAsync(status = "import_ui_error", message = "Xtream server URL is blank")
            return
        }
        if (!baseUrl.startsWith("http://", ignoreCase = true) && !baseUrl.startsWith("https://", ignoreCase = true)) {
            _uiState.update { it.copy(lastError = "URL Xtream должен начинаться с http:// или https://") }
            logAsync(status = "import_ui_error", message = "Xtream server URL has no http scheme")
            return
        }
        if (state.xtreamUsername.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите логин Xtream") }
            logAsync(status = "import_ui_error", message = "Xtream username is blank")
            return
        }
        if (state.xtreamPassword.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите пароль Xtream") }
            logAsync(status = "import_ui_error", message = "Xtream password is blank")
            return
        }

        executeImport(importKind = "xtream", source = "$baseUrl/player_api.php") {
            val insecureAllowed = settingsRepository.observeAllowInsecureUrls().first()
            if (!isSecureOrLocalUrl(baseUrl) && !insecureAllowed) {
                return@executeImport AppResult.Error(
                    "Безопасный режим: разрешены HTTPS URL. Для HTTP включите настройку 'Разрешить HTTP URL'."
                )
            }
            playlistRepository.importFromXtream(
                baseUrl = baseUrl,
                username = state.xtreamUsername.trim(),
                password = state.xtreamPassword.trim(),
                name = state.playlistName.trim()
            )
        }
    }

    fun importFromStalker() {
        if (_uiState.value.isLoading) {
            logAsync(status = "import_click_ignored", message = "Import already running (stalker)")
            return
        }
        val state = _uiState.value
        val portalUrl = state.stalkerPortalUrl.trim().trimEnd('/')
        if (portalUrl.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите URL Stalker Portal") }
            logAsync(status = "import_ui_error", message = "Stalker portal URL is blank")
            return
        }
        if (!portalUrl.startsWith("http://", ignoreCase = true) && !portalUrl.startsWith("https://", ignoreCase = true)) {
            _uiState.update { it.copy(lastError = "URL Stalker должен начинаться с http:// или https://") }
            logAsync(status = "import_ui_error", message = "Stalker portal URL has no http scheme")
            return
        }
        if (state.stalkerMacAddress.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите MAC Stalker/MAG") }
            logAsync(status = "import_ui_error", message = "Stalker MAC is blank")
            return
        }

        executeImport(importKind = "stalker", source = portalUrl) {
            val insecureAllowed = settingsRepository.observeAllowInsecureUrls().first()
            if (!isSecureOrLocalUrl(portalUrl) && !insecureAllowed) {
                return@executeImport AppResult.Error(
                    "Безопасный режим: разрешены HTTPS URL. Для HTTP включите настройку 'Разрешить HTTP URL'."
                )
            }
            playlistRepository.importFromStalker(
                portalUrl = portalUrl,
                macAddress = state.stalkerMacAddress.trim(),
                name = state.playlistName.trim()
            )
        }
    }

    fun saveXtreamProvider() {
        val state = _uiState.value
        val baseUrl = state.xtreamBaseUrl.trim().trimEnd('/')
        if (baseUrl.isBlank() || state.xtreamUsername.isBlank() || state.xtreamPassword.isBlank()) {
            _uiState.update { it.copy(lastError = "Заполните URL, логин и пароль Xtream") }
            return
        }
        saveProvider(
            PlaylistProvider(
                id = 0,
                type = ProviderType.XTREAM,
                name = state.playlistName.trim().ifBlank { "Xtream Codes" },
                baseUrl = baseUrl,
                username = state.xtreamUsername.trim(),
                password = state.xtreamPassword.trim(),
                token = null,
                macAddress = null,
                authType = ProviderAuthType.USER_PASSWORD,
                linkedPlaylistId = null,
                lastSyncedAt = null,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun saveStalkerProvider() {
        val state = _uiState.value
        val portalUrl = state.stalkerPortalUrl.trim().trimEnd('/')
        if (portalUrl.isBlank() || state.stalkerMacAddress.isBlank()) {
            _uiState.update { it.copy(lastError = "Заполните URL портала и MAC Stalker") }
            return
        }
        saveProvider(
            PlaylistProvider(
                id = 0,
                type = ProviderType.STALKER,
                name = state.playlistName.trim().ifBlank { "Stalker Portal" },
                baseUrl = portalUrl,
                username = null,
                password = null,
                token = null,
                macAddress = state.stalkerMacAddress.trim(),
                authType = ProviderAuthType.MAC_ADDRESS,
                linkedPlaylistId = null,
                lastSyncedAt = null,
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun syncProvider(providerId: Long) {
        if (_uiState.value.syncingProviderId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(syncingProviderId = providerId, lastError = null, providerMessage = null) }
            safeLog(status = "provider_sync_start", message = "providerId=$providerId")
            when (val result = providerAccountRepository.syncProvider(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            syncingProviderId = null,
                            providerMessage = "Провайдер синхронизирован, playlistId=${result.data}"
                        )
                    }
                    loadContentSummary(result.data)
                    safeLog(status = "provider_sync_ok", message = "providerId=$providerId, playlistId=${result.data}")
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(syncingProviderId = null, lastError = result.message)
                    }
                    safeLog(status = "provider_sync_error", message = "providerId=$providerId, reason=${result.message}")
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun checkProvider(providerId: Long) {
        if (_uiState.value.checkingProviderId != null) return
        viewModelScope.launch {
            _uiState.update { it.copy(checkingProviderId = providerId, lastError = null, providerMessage = null) }
            safeLog(status = "provider_check_start", message = "providerId=$providerId")
            when (val result = providerAccountRepository.checkProvider(providerId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            checkingProviderId = null,
                            lastProviderStatus = result.data,
                            providerMessage = "Проверка провайдера: ${result.data.statusText}"
                        )
                    }
                    safeLog(
                        status = "provider_check_ok",
                        message = "providerId=$providerId, ok=${result.data.ok}, status=${result.data.statusText}"
                    )
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(checkingProviderId = null, lastError = result.message)
                    }
                    safeLog(status = "provider_check_error", message = "providerId=$providerId, reason=${result.message}")
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun deleteProvider(providerId: Long) {
        viewModelScope.launch {
            when (val result = providerAccountRepository.deleteProvider(providerId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(providerMessage = "Провайдер удалён", lastError = null) }
                    safeLog(status = "provider_delete_ok", message = "providerId=$providerId, deleted=${result.data}")
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(lastError = result.message) }
                    safeLog(status = "provider_delete_error", message = "providerId=$providerId, reason=${result.message}")
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun importFromText() {
        if (_uiState.value.isLoading) {
            logAsync(status = "import_click_ignored", message = "Import already running (text)")
            return
        }
        val state = _uiState.value
        if (state.rawText.isBlank()) {
            _uiState.update { it.copy(lastError = "Вставьте текст плейлиста") }
            logAsync(status = "import_ui_error", message = "Text payload is blank")
            return
        }
        executeImport(
            importKind = "text",
            source = "rawTextLength=${state.rawText.length}"
        ) { playlistRepository.importFromText(state.rawText, state.playlistName.trim()) }
    }

    fun importFromFile() {
        if (_uiState.value.isLoading) {
            logAsync(status = "import_click_ignored", message = "Import already running (file)")
            return
        }
        val state = _uiState.value
        if (state.filePathOrUri.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите путь к файлу или content:// URI") }
            logAsync(status = "import_ui_error", message = "File path/uri is blank")
            return
        }
        executeImport(importKind = "file", source = state.filePathOrUri.trim()) {
            playlistRepository.importFromFile(state.filePathOrUri.trim(), state.playlistName.trim())
        }
    }

    fun onStoragePermissionDenied() {
        _uiState.update {
            it.copy(lastError = "Доступ к памяти не выдан. Выберите файл через системный диалог или разрешите доступ.")
        }
        logAsync(status = "import_permission_denied", message = "Storage permission denied by user")
    }

    fun validateLastImportedPlaylist() {
        val playlistId = _uiState.value.lastImportReport?.playlistId
        if (playlistId == null) {
            _uiState.update { it.copy(lastError = "Сначала выполните импорт") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            safeLog(
                status = "validation_start",
                message = "playlistId=$playlistId"
            )
            when (val result = playlistRepository.validatePlaylist(playlistId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastValidationReport = result.data
                        )
                    }
                    safeLog(
                        status = "validation_ok",
                        message = "playlistId=$playlistId, checked=${result.data.totalChecked}, available=${result.data.available}, unstable=${result.data.unstable}, unavailable=${result.data.unavailable}"
                    )
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastError = result.message
                        )
                    }
                    safeLog(
                        status = "validation_error",
                        message = "playlistId=$playlistId, reason=${result.message}"
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeImport(
        importKind: String,
        source: String,
        action: suspend () -> AppResult<PlaylistImportReport>
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastValidationReport = null) }
            safeLog(
                status = "import_start",
                message = "kind=$importKind, source=${source.take(MAX_LOG_MESSAGE)}, name=${_uiState.value.playlistName.trim()}"
            )
            val startedAt = System.currentTimeMillis()
            val watchdog = launch {
                delay(IMPORT_WATCHDOG_MS)
                if (_uiState.value.isLoading) {
                    safeLog(
                        status = "import_watchdog",
                        message = "kind=$importKind still running after ${IMPORT_WATCHDOG_MS / 1000}s"
                    )
                }
            }
            val result = try {
                withTimeout(IMPORT_TIMEOUT_MS) {
                    action()
                }
            } catch (_: TimeoutCancellationException) {
                AppResult.Error("Импорт превысил лимит ожидания (${IMPORT_TIMEOUT_MS / 1000}с)")
            }
            when (result) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastImportReport = result.data,
                            lastContentSummary = null,
                            lastError = null
                        )
                    }
                    loadContentSummary(result.data.playlistId)
                    safeLog(
                        status = "import_ok",
                        message = "kind=$importKind, playlistId=${result.data.playlistId}, imported=${result.data.totalImported}, parsed=${result.data.totalParsed}, duplicates=${result.data.removedDuplicates}"
                    )
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastError = result.message
                        )
                    }
                    safeLog(
                        status = "import_error",
                        message = "kind=$importKind, source=${source.take(MAX_LOG_MESSAGE)}, reason=${result.message}"
                    )
                }
                AppResult.Loading -> Unit
            }
            watchdog.cancel()
            safeLog(
                status = "import_finish",
                message = "kind=$importKind, durationMs=${System.currentTimeMillis() - startedAt}, isLoading=${_uiState.value.isLoading}"
            )
        }
    }

    private fun observeProviders() {
        viewModelScope.launch {
            providerAccountRepository.observeProviders().collect { providers ->
                _uiState.update { it.copy(savedProviders = providers) }
            }
        }
    }

    private fun saveProvider(provider: PlaylistProvider) {
        viewModelScope.launch {
            _uiState.update { it.copy(lastError = null, providerMessage = null) }
            when (val result = providerAccountRepository.saveProvider(provider)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(providerMessage = "Провайдер сохранён") }
                    safeLog(status = "provider_save_ok", message = "providerId=${result.data}, type=${provider.type}")
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(lastError = result.message) }
                    safeLog(status = "provider_save_error", message = "type=${provider.type}, reason=${result.message}")
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun applyScannerPrefill() {
        val prefill = ImportPrefillBus.consume() ?: return
        val prefillUrl = prefill.url.trim()
        if (prefillUrl.isBlank()) return

        _uiState.update { state ->
            state.copy(
                url = prefillUrl,
                playlistName = prefill.playlistName.trim().ifBlank { state.playlistName },
                lastError = null
            )
        }

        if (prefill.autoImport) {
            importFromUrl()
        }
    }

    private fun loadContentSummary(playlistId: Long) {
        viewModelScope.launch {
            when (val result = playlistRepository.getPlaylistContentSummary(playlistId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(lastContentSummary = result.data) }
                }
                is AppResult.Error -> {
                    safeLog(
                        status = "import_summary_error",
                        message = "playlistId=$playlistId, reason=${result.message}",
                        playlistId = playlistId
                    )
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun isSecureOrLocalUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.startsWith("https://") ||
            normalized.startsWith("http://127.0.0.1") ||
            normalized.startsWith("http://localhost")
    }

    private fun logAsync(status: String, message: String) {
        viewModelScope.launch {
            safeLog(status = status, message = message)
        }
    }

    private suspend fun safeLog(status: String, message: String, playlistId: Long? = null) {
        runCatching {
            diagnosticsRepository.addLog(
                status = status,
                message = message.take(MAX_LOG_MESSAGE),
                playlistId = playlistId
            )
        }
    }

    private companion object {
        const val MAX_LOG_MESSAGE = 700
        const val IMPORT_TIMEOUT_MS = 300_000L
        const val IMPORT_WATCHDOG_MS = 10_000L
    }
}
