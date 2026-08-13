package com.iptv.tv.feature.home

import com.iptv.tv.core.model.Playlist

internal fun findImportedReadyPlaylist(
    playlists: List<Playlist>,
    sourceKey: String
): Playlist? = playlists.firstOrNull { playlist ->
    playlist.source.trim() == sourceKey.trim()
}
