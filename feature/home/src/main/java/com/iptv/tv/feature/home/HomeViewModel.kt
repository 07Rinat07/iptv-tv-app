package com.iptv.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val title: String = "Смотреть ТВ",
    val description: String = "Выберите сохранённый или готовый список каналов — после загрузки сразу откроется плеер.",
    val playlists: List<Playlist> = emptyList(),
    val isImporting: Boolean = false,
    val importingUrl: String? = null,
    val lastError: String? = null,
    val lastInfo: String? = null,
    val pendingOpenPlaylistId: Long? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { state -> state.copy(playlists = playlists) }
            }
        }
    }

    fun watchReadyPlaylist(preset: ReadyPlaylistPreset) {
        val existing = findImportedReadyPlaylist(
            playlists = _uiState.value.playlists,
            sourceUrl = preset.url
        )
        if (existing != null) {
            requestOpenPlaylist(existing.id, "Открывается сохранённый список: ${existing.name}")
            return
        }
        if (_uiState.value.isImporting) return

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isImporting = true,
                    importingUrl = preset.url,
                    lastError = null,
                    lastInfo = "Загрузка списка «${preset.name}»…"
                )
            }
            when (val result = playlistRepository.importFromUrl(preset.url, preset.name)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importingUrl = null,
                            lastInfo = "Список добавлен. Открывается плеер.",
                            pendingOpenPlaylistId = result.data.playlistId
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isImporting = false,
                            importingUrl = null,
                            lastError = "Не удалось загрузить «${preset.name}»: ${result.message}",
                            lastInfo = null
                        )
                    }
                }
                AppResult.Loading -> {
                    _uiState.update { it.copy(isImporting = false, importingUrl = null) }
                }
            }
        }
    }

    fun requestOpenPlaylist(playlistId: Long, message: String? = null) {
        _uiState.update {
            it.copy(
                pendingOpenPlaylistId = playlistId,
                lastInfo = message,
                lastError = null
            )
        }
    }

    fun consumeOpenPlaylistRequest() {
        _uiState.update { it.copy(pendingOpenPlaylistId = null) }
    }
}
