package com.iptv.tv.feature.importer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.PlaylistImportReport
import com.iptv.tv.core.model.PlaylistValidationReport
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    private val settingsRepository: SettingsRepository
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
        val state = _uiState.value
        if (state.url.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите URL") }
            return
        }
        executeImport {
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
        val state = _uiState.value
        if (state.rawText.isBlank()) {
            _uiState.update { it.copy(lastError = "Вставьте текст плейлиста") }
            return
        }
        executeImport { playlistRepository.importFromText(state.rawText, state.playlistName.trim()) }
    }

    fun importFromFile() {
        val state = _uiState.value
        if (state.filePathOrUri.isBlank()) {
            _uiState.update { it.copy(lastError = "Укажите путь к файлу или content:// URI") }
            return
        }
        executeImport { playlistRepository.importFromFile(state.filePathOrUri.trim(), state.playlistName.trim()) }
    }

    fun validateLastImportedPlaylist() {
        val playlistId = _uiState.value.lastImportReport?.playlistId
        if (playlistId == null) {
            _uiState.update { it.copy(lastError = "Сначала выполните импорт") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null) }
            when (val result = playlistRepository.validatePlaylist(playlistId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastValidationReport = result.data
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastError = result.message
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun executeImport(action: suspend () -> AppResult<PlaylistImportReport>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, lastError = null, lastValidationReport = null) }
            when (val result = action()) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastImportReport = result.data,
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            lastError = result.message
                        )
                    }
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

    private fun isSecureOrLocalUrl(url: String): Boolean {
        val normalized = url.trim().lowercase()
        return normalized.startsWith("https://") ||
            normalized.startsWith("http://127.0.0.1") ||
            normalized.startsWith("http://localhost")
    }
}
