package com.iptv.tv.feature.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistValidationReport
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
    val filePathOrUri: String = "",
    val rawText: String = "",
    val isLoading: Boolean = false,
    val lastError: String? = null,
    val lastImportReport: PlaylistImportReport? = null,
    val lastValidationReport: PlaylistValidationReport? = null
)

@HiltViewModel
class ImporterViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository,
    private val settingsRepository: SettingsRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(ImporterUiState())
    val uiState: StateFlow<ImporterUiState> = _uiState.asStateFlow()

    init {
        applyScannerPrefill()
    }

    fun updatePlaylistName(value: String) = _uiState.update { it.copy(playlistName = value) }
    fun updateUrl(value: String) = _uiState.update { it.copy(url = value) }
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
                            lastError = null
                        )
                    }
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
