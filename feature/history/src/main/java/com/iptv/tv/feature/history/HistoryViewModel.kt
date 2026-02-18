package com.iptv.tv.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.PlaybackHistoryItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HistoryEntryUi(
    val history: PlaybackHistoryItem,
    val channel: Channel?
)

data class HistoryUiState(
    val title: String = "История",
    val description: String = "История просмотров каналов",
    val entries: List<HistoryEntryUi> = emptyList(),
    val selectedHistoryId: Long? = null,
    val lastInfo: String? = null,
    val lastError: String? = null
)

@HiltViewModel
class HistoryViewModel @Inject constructor(
    private val historyRepository: HistoryRepository,
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HistoryUiState())
    val uiState: StateFlow<HistoryUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            historyRepository.observeHistory(limit = 250).collect { historyItems ->
                val entries = historyItems.map { item ->
                    val channel = when (val channelResult = playlistRepository.getChannelById(item.channelId)) {
                        is AppResult.Success -> channelResult.data
                        else -> null
                    }
                    HistoryEntryUi(history = item, channel = channel)
                }
                _uiState.update { state ->
                    val selected = state.selectedHistoryId?.takeIf { id -> entries.any { it.history.id == id } }
                    state.copy(
                        entries = entries,
                        selectedHistoryId = selected ?: entries.firstOrNull()?.history?.id
                    )
                }
            }
        }
    }

    fun selectHistory(historyId: Long) {
        _uiState.update { it.copy(selectedHistoryId = historyId, lastError = null) }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyRepository.clear()
            _uiState.update { it.copy(lastInfo = "История очищена", lastError = null) }
        }
    }

    fun selectedPlaylistId(): Long? {
        val selectedId = _uiState.value.selectedHistoryId ?: return null
        return _uiState.value.entries
            .firstOrNull { it.history.id == selectedId }
            ?.channel
            ?.playlistId
    }
}

