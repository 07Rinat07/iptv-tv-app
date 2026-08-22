package com.iptv.tv.feature.playlists

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogNodeId
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    val isCatalogOpen: Boolean = false,
    val isLoadingCatalog: Boolean = false,
    val catalog: PlaylistCatalogSnapshot? = null,
    val lastError: String? = null,
    val lastInfo: String? = null
)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    private val playlistRepository: PlaylistRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(PlaylistsUiState())
    val uiState: StateFlow<PlaylistsUiState> = _uiState.asStateFlow()

    private var catalogJob: Job? = null
    private var boundCatalogPlaylist: Playlist? = null
    private var catalogNavigation: PlaylistCatalogNavigationSession? = null

    init {
        viewModelScope.launch {
            playlistRepository.observePlaylists().collect { playlists ->
                var selectedPlaylist: Playlist? = null
                _uiState.update { current ->
                    val selected = current.selectedPlaylistId?.takeIf { id -> playlists.any { it.id == id } }
                    val effectiveSelected = selected ?: playlists.firstOrNull()?.id
                    val selectionChanged = current.selectedPlaylistId != effectiveSelected
                    selectedPlaylist = playlists.firstOrNull { it.id == effectiveSelected }
                    current.copy(
                        playlists = playlists,
                        selectedPlaylistId = effectiveSelected,
                        selectedSummary = current.selectedSummary?.takeIf { it.playlistId == effectiveSelected },
                        isCatalogOpen = if (selectionChanged) false else current.isCatalogOpen
                    )
                }
                bindCatalog(selectedPlaylist)
                _uiState.value.selectedPlaylistId?.let { loadSummary(it) }
            }
        }
    }

    fun selectPlaylist(playlistId: Long) {
        val selectedPlaylist = _uiState.value.playlists.firstOrNull { it.id == playlistId }
        _uiState.update {
            it.copy(
                selectedPlaylistId = playlistId,
                selectedSummary = null,
                isCatalogOpen = false,
                lastError = null,
                lastInfo = null
            )
        }
        bindCatalog(selectedPlaylist)
        loadSummary(playlistId)
    }

    fun openSelectedCatalog() {
        val catalog = _uiState.value.catalog
        if (catalog == null) {
            _uiState.update {
                it.copy(
                    lastError = if (it.isLoadingCatalog) {
                        "Каталог выбранного плейлиста ещё загружается"
                    } else {
                        "Каталог выбранного плейлиста недоступен"
                    }
                )
            }
            return
        }
        _uiState.update { it.copy(isCatalogOpen = true, lastError = null, lastInfo = null) }
    }

    fun closeCatalog() {
        _uiState.update { it.copy(isCatalogOpen = false) }
    }

    /**
     * Handles one Back action while the canonical catalog is open.
     * Returns false only when this feature has no catalog Back action to consume.
     */
    fun handleCatalogBack(): Boolean {
        if (!_uiState.value.isCatalogOpen) return false
        val navigation = catalogNavigation
        if (navigation != null && navigation.back()) {
            publishCatalogSnapshot(navigation)
        } else {
            closeCatalog()
        }
        return true
    }

    fun enterCatalogNode(nodeId: CatalogNodeId) {
        val navigation = catalogNavigation ?: return
        if (navigation.enter(nodeId)) publishCatalogSnapshot(navigation)
    }

    fun focusCatalogNode(nodeId: CatalogNodeId) {
        val navigation = catalogNavigation ?: return
        val currentSnapshot = _uiState.value.catalog ?: return
        val focusedSnapshot = runCatching {
            navigation.focus(nodeId = nodeId, currentSnapshot = currentSnapshot)
        }.getOrNull() ?: return
        _uiState.update { current ->
            if (current.catalog !== currentSnapshot) {
                current
            } else {
                current.copy(catalog = focusedSnapshot)
            }
        }
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

    private fun bindCatalog(playlist: Playlist?) {
        if (playlist == null) {
            catalogJob?.cancel()
            catalogJob = null
            boundCatalogPlaylist = null
            catalogNavigation = null
            _uiState.update {
                it.copy(
                    isCatalogOpen = false,
                    isLoadingCatalog = false,
                    catalog = null
                )
            }
            return
        }

        if (boundCatalogPlaylist == playlist && catalogJob?.isActive == true) return

        val previousCheckpoint = catalogNavigation
            ?.takeIf { navigation -> navigation.snapshot().playlistId == playlist.id }
            ?.checkpoint()
        catalogJob?.cancel()
        boundCatalogPlaylist = playlist
        _uiState.update { current ->
            current.copy(
                isLoadingCatalog = true,
                catalog = current.catalog?.takeIf { it.playlistId == playlist.id }
            )
        }
        catalogJob = viewModelScope.launch {
            playlistRepository.observeChannels(playlist.id).collect { channels ->
                val checkpoint = catalogNavigation
                    ?.takeIf { navigation -> navigation.snapshot().playlistId == playlist.id }
                    ?.checkpoint()
                    ?: previousCheckpoint
                val navigation = PlaylistCatalogNavigationSession.create(
                    playlist = playlist,
                    channels = channels,
                    previousCheckpoint = checkpoint
                )
                catalogNavigation = navigation
                publishCatalogSnapshot(navigation)
            }
        }
    }

    private fun publishCatalogSnapshot(navigation: PlaylistCatalogNavigationSession) {
        val snapshot = navigation.snapshot()
        _uiState.update { current ->
            if (current.selectedPlaylistId != snapshot.playlistId) {
                current
            } else {
                current.copy(
                    catalog = snapshot,
                    isLoadingCatalog = false
                )
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
