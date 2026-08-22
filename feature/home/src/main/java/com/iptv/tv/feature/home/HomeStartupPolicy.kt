package com.iptv.tv.feature.home

import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Playlist

internal fun findImportedReadyPlaylist(
    playlists: List<Playlist>,
    sourceUrl: String
): Playlist? = playlists.firstOrNull { playlist ->
    playlist.catalogOrigin == CatalogOriginKind.READY_CATALOG &&
        playlist.source.trim() == sourceUrl.trim()
}
