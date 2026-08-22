package com.iptv.tv.core.model

/**
 * Stable non-Room identities for system-owned aggregate playlists.
 *
 * Room playlist IDs are positive auto-generated values. Virtual aggregate views use reserved
 * negative IDs so they can flow through the existing Player/navigation contracts without being
 * persisted as physical playlists.
 */
const val VIRTUAL_ALL_CHANNELS_PLAYLIST_ID: Long = -9_000_000_000_044L
const val VIRTUAL_ALL_CHANNELS_SOURCE: String = "virtual://all-channels"
const val VIRTUAL_FAVORITES_PLAYLIST_ID: Long = -9_000_000_000_045L
const val VIRTUAL_FAVORITES_SOURCE: String = "virtual://favorites"
const val VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID: Long = -9_000_000_000_046L
const val VIRTUAL_RECENT_CHANNELS_SOURCE: String = "virtual://recent-channels"

fun isSystemVirtualPlaylistId(playlistId: Long): Boolean {
    return playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID ||
        playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID ||
        playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
}
