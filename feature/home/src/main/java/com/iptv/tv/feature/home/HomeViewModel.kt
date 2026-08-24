package com.iptv.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.Playlist
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val title: String = "Смотреть ТВ",
    val description: String = "Выберите сохранённый или готовый список каналов — после загрузки сразу откроется плеер.",
    val playlists: List<Playlist> = emptyList(),
    val channelRailPlaylistId: Long? = null,
    val channelRailChannels: List<Channel> = emptyList(),
    val channelRailSelectedChannelId: Long? = null,
    val isImporting: Boolean = false,
    val importingUrl: String? = null,
    val lastError: String? = null,
    val lastInfo: String? = null,
    val pendingOpenPlaylistId: Long? = null,
    val pendingOpenChannelId: Long? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()
    private val _channelRailPlaylistId = MutableStateFlow<Long?>(null)

    init {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                val currentRailPlaylistId = _uiState.value.channelRailPlaylistId
                val nextRailPlaylistId = currentRailPlaylistId
                    ?.takeIf { id -> playlists.any { it.id == id } }
                    ?: playlists.firstOrNull()?.id

                _uiState.update { state ->
                    val railSourceChanged = state.channelRailPlaylistId != nextRailPlaylistId
                    state.copy(
                        playlists = playlists,
                        channelRailPlaylistId = nextRailPlaylistId,
                        channelRailChannels = if (railSourceChanged) emptyList() else state.channelRailChannels,
                        channelRailSelectedChannelId = if (railSourceChanged) {
                            null
                        } else {
                            state.channelRailSelectedChannelId
                        }
                    )
                }
                _channelRailPlaylistId.value = nextRailPlaylistId
            }
        }

        viewModelScope.launch {
            _channelRailPlaylistId
                .flatMapLatest { playlistId ->
                    if (playlistId == null) {
                        flowOf(emptyList())
                    } else {
                        playlistRepository.observeChannels(playlistId)
                    }
                }
                .collect { channels ->
                    _uiState.update { state ->
                        val selectedChannelId = state.channelRailSelectedChannelId?.takeIf { id ->
                            channels.any { channel -> channel.id == id && !channel.isHidden }
                        }
                        state.copy(
                            channelRailChannels = channels,
                            channelRailSelectedChannelId = selectedChannelId
                        )
                    }
                }
        }
    }

    fun watchReadyPlaylist(preset: ReadyPlaylistPreset) {
        if (_uiState.value.isImporting) return
        val existing = findImportedReadyPlaylist(
            playlists = _uiState.value.playlists,
            sourceUrl = preset.url
        )

        viewModelScope.launch {
            beginReadyPlaylistLoad(preset)
            if (existing != null) {
                refreshReadyPlaylistBeforeOpen(
                    playlistId = existing.id,
                    presetName = preset.name,
                    successMessage = "Список обновлён. Открывается плеер."
                )
                return@launch
            }

            when (
                val result = playlistRepository.importFromUrl(
                    url = preset.url,
                    name = preset.name,
                    catalogOrigin = CatalogOriginKind.READY_CATALOG
                )
            ) {
                is AppResult.Success -> {
                    // Generic URL import deliberately keeps its legacy semantics. Ready refresh has
                    // stricter stream-identity handling, so run it once before the very first open:
                    // primary/backup/quality variants that share tvg-id/name are then present from
                    // the first user-visible session rather than only after a later refresh.
                    refreshReadyPlaylistBeforeOpen(
                        playlistId = result.data.playlistId,
                        presetName = preset.name,
                        successMessage = "Список добавлен и обновлён. Открывается плеер."
                    )
                }
                is AppResult.Error -> failReadyPlaylistLoad(
                    "Не удалось загрузить «${preset.name}»: ${result.message}"
                )
                AppResult.Loading -> clearReadyPlaylistLoading()
            }
        }
    }

    fun requestOpenPlaylist(playlistId: Long, message: String? = null) {
        selectChannelRailPlaylist(playlistId)
        _uiState.update {
            it.copy(
                pendingOpenPlaylistId = playlistId,
                pendingOpenChannelId = null,
                lastInfo = message,
                lastError = null
            )
        }
    }

    fun requestOpenChannel(playlistId: Long, channelId: Long) {
        selectChannelRailPlaylist(playlistId)
        _uiState.update {
            it.copy(
                channelRailSelectedChannelId = channelId,
                pendingOpenPlaylistId = playlistId,
                pendingOpenChannelId = channelId,
                lastError = null
            )
        }
    }

    fun consumeOpenPlaylistRequest() {
        _uiState.update {
            it.copy(
                pendingOpenPlaylistId = null,
                pendingOpenChannelId = null
            )
        }
    }

    private suspend fun refreshReadyPlaylistBeforeOpen(
        playlistId: Long,
        presetName: String,
        successMessage: String
    ) {
        when (val refresh = playlistRepository.refreshPlaylist(playlistId)) {
            is AppResult.Success -> completeReadyPlaylistLoad(
                playlistId = playlistId,
                message = successMessage
            )
            is AppResult.Error -> failReadyPlaylistLoad(
                "Не удалось обновить «$presetName»: ${refresh.message}"
            )
            AppResult.Loading -> clearReadyPlaylistLoading()
        }
    }

    private fun beginReadyPlaylistLoad(preset: ReadyPlaylistPreset) {
        _uiState.update {
            it.copy(
                isImporting = true,
                importingUrl = preset.url,
                lastError = null,
                lastInfo = "Загрузка списка «${preset.name}»…"
            )
        }
    }

    private fun completeReadyPlaylistLoad(playlistId: Long, message: String) {
        selectChannelRailPlaylist(playlistId)
        _uiState.update {
            it.copy(
                isImporting = false,
                importingUrl = null,
                lastInfo = message,
                lastError = null,
                pendingOpenPlaylistId = playlistId,
                pendingOpenChannelId = null
            )
        }
    }

    private fun failReadyPlaylistLoad(message: String) {
        _uiState.update {
            it.copy(
                isImporting = false,
                importingUrl = null,
                lastError = message,
                lastInfo = null
            )
        }
    }

    private fun clearReadyPlaylistLoading() {
        _uiState.update {
            it.copy(
                isImporting = false,
                importingUrl = null
            )
        }
    }

    private fun selectChannelRailPlaylist(playlistId: Long) {
        _channelRailPlaylistId.value = playlistId
        _uiState.update { state ->
            if (state.channelRailPlaylistId == playlistId) {
                state
            } else {
                state.copy(
                    channelRailPlaylistId = playlistId,
                    channelRailChannels = emptyList(),
                    channelRailSelectedChannelId = null
                )
            }
        }
    }
}
