package com.iptv.tv.feature.playlists

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel

/**
 * Backward-compatible app-shell adapter while canonical channel deep-link routing is introduced as
 * a separate roadmap increment. Existing callers that only know playlistId keep compiling and a
 * channel click still opens that playlist in Player; callers can opt into the two-argument
 * PlaylistsScreen overload to open an exact channel.
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
