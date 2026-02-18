package com.iptv.tv.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.model.Channel
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
    val selectedChannelId: Long? = null,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class FavoritesViewModel @Inject constructor(
    private val favoritesRepository: FavoritesRepository
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
                        selectedChannelId = selectedId ?: channels.firstOrNull()?.id
                    )
                }
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
}

