package com.iptv.tv.feature.playlists

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Backward-compatible adapter for callers that only know playlistId. The app shell uses the
 * two-argument PlaylistsScreen overload for exact-channel navigation; older callers keep compiling
 * and intentionally ignore the optional canonical channel leaf.
 */
@Composable
fun PlaylistsScreen(
    onOpenEditor: ((Long) -> Unit)?,
    onOpenPlayer: ((Long) -> Unit)?,
    viewModel: PlaylistsViewModel = hiltViewModel()
) {
    PlaylistsScreen(
        onOpenEditor = onOpenEditor,
        onOpenPlayer = { playlistId, _ -> onOpenPlayer?.invoke(playlistId) },
        viewModel = viewModel
    )
}
