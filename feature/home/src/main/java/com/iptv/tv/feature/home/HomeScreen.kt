package com.iptv.tv.feature.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun HomeScreen(
    onOpenScanner: (() -> Unit)? = null,
    onOpenImporter: (() -> Unit)? = null,
    onOpenReadyPlaylists: (() -> Unit)? = null,
    onOpenPlaylists: (() -> Unit)? = null,
    onOpenPlaylist: ((Long) -> Unit)? = null,
    onOpenChannel: ((Long, Long) -> Unit)? = null,
    onOpenEpg: (() -> Unit)? = null,
    onOpenPlayer: (() -> Unit)? = null,
    onOpenSettings: (() -> Unit)? = null,
    onOpenDiagnostics: (() -> Unit)? = null,
    onPrimaryAction: (() -> Unit)? = null,
    primaryLabel: String = "Открыть",
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsState()

    LaunchedEffect(state.pendingOpenPlaylistId, state.pendingOpenChannelId) {
        val playlistId = state.pendingOpenPlaylistId ?: return@LaunchedEffect
        val channelId = state.pendingOpenChannelId
        when {
            channelId != null && onOpenChannel != null -> onOpenChannel(playlistId, channelId)
            onOpenPlaylist != null -> onOpenPlaylist(playlistId)
            else -> onOpenPlayer?.invoke()
        }
        viewModel.consumeOpenPlaylistRequest()
    }

    HomeReadmeDashboard(
        state = state,
        onWatchPlaylist = { playlistId -> viewModel.requestOpenPlaylist(playlistId) },
        onWatchChannel = viewModel::requestOpenChannel,
        onWatchReadyPlaylist = viewModel::watchReadyPlaylist,
        onOpenScanner = onOpenScanner,
        onOpenImporter = onOpenImporter,
        onOpenReadyPlaylists = onOpenReadyPlaylists,
        onOpenPlaylists = onOpenPlaylists,
        onOpenEpg = onOpenEpg,
        onOpenPlayer = onOpenPlayer,
        onOpenSettings = onOpenSettings,
        onOpenDiagnostics = onOpenDiagnostics,
        onPrimaryAction = onPrimaryAction,
        primaryLabel = primaryLabel
    )
}
