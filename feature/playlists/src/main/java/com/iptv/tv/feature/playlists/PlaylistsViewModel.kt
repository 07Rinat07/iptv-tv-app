package com.iptv.tv.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistsUiState(
    val title: String = "Мои плейлисты",
    val description: String = "Выберите список для редактирования или обновления",
    val playlists: List<Playlist> = emptyList(),
    val selectedPlaylistId: Long? = null,
    val selectedSummary: PlaylistContentSummary? = null,
    val isLoadingSummary: Boolean = false,
    val isRefreshing: Boolean = false,
    val isDeleting: Boolean = false,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                _uiState.update { current ->
                    val selected = current.selectedPlaylistId?.takeIf { id -> playlists.any { it.id == id } }
                    val effectiveSelected = selected ?: playlists.firstOrNull()?.id
                    current.copy(
                        playlists = playlists,
                        selectedPlaylistId = effectiveSelected,
                        selectedSummary = current.selectedSummary?.takeIf { it.playlistId == effectiveSelected }
                    )
                }
                _uiState.value.selectedPlaylistId?.let { loadSummary(it) }
            }
        }
    }

    fun selectPlaylist(playlistId: Long) {
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlistId,
                selectedSummary = null,
                lastError = null,
                lastInfo = null
            )
        }
        loadSummary(playlistId)
    }

    fun refreshSelectedPlaylist() {
        val selectedId = _uiState.value.selectedPlaylistId
        if (selectedId == null) {
            _uiState.update { it.copy(lastError = "Плейлист не выбран") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isRefreshing = true, lastError = null, lastInfo = null) }
            when (val result = playlistRepository.refreshPlaylist(selectedId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            lastInfo = "Обновление запущено",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isRefreshing = false,
                            lastError = result.message
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    fun deleteSelectedPlaylist() {
        val selectedId = _uiState.value.selectedPlaylistId
        if (selectedId == null) {
            _uiState.update { it.copy(lastError = "Плейлист не выбран") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, lastError = null, lastInfo = null) }
            when (val result = playlistRepository.deletePlaylist(selectedId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            lastInfo = "Плейлист удален, каналов удалено: ${result.data}",
                            lastError = null
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            lastError = result.message
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }

    private fun loadSummary(playlistId: Long) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingSummary = true) }
            when (val result = playlistRepository.getPlaylistContentSummary(playlistId)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            selectedSummary = result.data,
                            isLoadingSummary = false
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoadingSummary = false,
                            lastError = result.message
                        )
                    }
                }
                AppResult.Loading -> Unit
            }
        }
    }
}
