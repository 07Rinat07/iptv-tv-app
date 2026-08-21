package com.iptv.tv.core.model

/**
 * Stable non-Room identity for the aggregate Favorites playlist.
 *
 * Room playlist IDs are positive auto-generated values. Virtual aggregate views use reserved
 * negative IDs so they can flow through the existing Player/navigation contracts without being
 * persisted as physical playlists.
 */
const val VIRTUAL_FAVORITES_PLAYLIST_ID: Long = -9_000_000_000_045L
const val VIRTUAL_FAVORITES_SOURCE: String = "virtual://favorites"
