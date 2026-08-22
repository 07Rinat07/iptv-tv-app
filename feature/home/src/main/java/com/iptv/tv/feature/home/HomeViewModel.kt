package com.iptv.tv.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
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
        _uiState.update {
            it.copy(
                isImporting = false,
                importingUrl = null,
                lastInfo = message,
                lastError = null,
                pendingOpenPlaylistId = playlistId
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
}
