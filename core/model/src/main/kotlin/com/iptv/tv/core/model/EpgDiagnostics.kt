package com.iptv.tv.core.model

/**
 * Source-level XMLTV matching diagnostics for one physical playlist.
 *
 * A channel is considered matched when the existing conservative matcher resolves it by
 * `tvg-id`, exact display name or channel-id. `channelsWithPrograms` is intentionally separate:
 * a valid channel match can exist even when that XMLTV channel currently has no programmes.
 */
data class PlaylistEpgDiagnostics(
    val playlistId: Long,
    val epgSourceUrl: String,
    val sourceLoadedAtMs: Long?,
    val totalChannels: Int,
    val matchedChannels: Int,
    val unmatchedChannels: Int,
    val tvgIdMatches: Int,
    val displayNameMatches: Int,
    val channelIdMatches: Int,
    val channelsWithPrograms: Int
)
