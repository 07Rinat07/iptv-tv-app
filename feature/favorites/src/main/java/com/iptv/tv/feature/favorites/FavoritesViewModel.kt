package com.iptv.tv.feature.favorites

import com.iptv.tv.core.common.AppResult
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUiState(
    val title: String = "Избранное",
    val description: String = "Глобальные избранные каналы",
    val channels: List<Channel> = emptyList(),
    val epgProgramsByChannel: Map<Long, List<EpgProgram>> = emptyMap(),
    val epgStatus: String = "EPG: нет данных",
    val selectedChannelId: Long? = null,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(FavoritesUiState())
    val uiState: StateFlow<FavoritesUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            favoritesRepository.observeFavorites().collect { channels ->
                _uiState.update { state ->
                    val selectedId = state.selectedChannelId?.takeIf { id -> channels.any { it.id == id } }
                    state.copy(
                        channels = channels,
                        epgProgramsByChannel = state.epgProgramsByChannel.filterKeys { id ->
                            channels.any { it.id == id }
                        },
                        selectedChannelId = selectedId ?: channels.firstOrNull()?.id
                    )
                }
                loadFavoritesEpg(channels)
            }
        }
    }

    fun selectChannel(channelId: Long) {
        _uiState.update { it.copy(selectedChannelId = channelId, lastError = null) }
    }

    fun removeSelectedFromFavorites() {
        val selected = _uiState.value.selectedChannelId
        if (selected == null) {
            _uiState.update { it.copy(lastError = "Канал не выбран") }
            return
        }
        viewModelScope.launch {
            favoritesRepository.toggleFavorite(selected)
            _uiState.update { it.copy(lastInfo = "Канал удален из избранного", lastError = null) }
        }
    }

    private fun loadFavoritesEpg(channels: List<Channel>) {
        if (channels.isEmpty()) {
            _uiState.update {
                it.copy(
                    epgProgramsByChannel = emptyMap(),
                    epgStatus = "EPG: нет избранных каналов"
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(epgStatus = "EPG: загрузка программы...") }
            val now = System.currentTimeMillis()
            val loaded = mutableMapOf<Long, List<EpgProgram>>()
            channels.groupBy { it.playlistId }.forEach { (playlistId, groupChannels) ->
                when (
                    val result = playlistRepository.getPlaylistEpgWindow(
                        playlistId = playlistId,
                        startEpochMs = now,
                        endEpochMs = now + FAVORITES_EPG_WINDOW_MS
                    )
                ) {
                    is AppResult.Success -> {
                        val favoriteIds = groupChannels.map { it.id }.toSet()
                        result.data
                            .filterKeys { it in favoriteIds }
                            .forEach { (channelId, programs) -> loaded[channelId] = programs }
                    }
                    is AppResult.Error, AppResult.Loading -> Unit
                }
            }
            _uiState.update {
                it.copy(
                    epgProgramsByChannel = loaded,
                    epgStatus = if (loaded.isEmpty()) {
                        "EPG: для избранных передач не найдено"
                    } else {
                        "EPG: найдено для каналов ${loaded.size}"
                    }
                )
            }
        }
    }
}

private const val FAVORITES_EPG_WINDOW_MS = 3 * 60 * 60 * 1000L
