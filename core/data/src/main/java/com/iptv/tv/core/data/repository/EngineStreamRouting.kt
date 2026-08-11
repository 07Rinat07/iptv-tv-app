package com.iptv.tv.core.data.repository

import com.iptv.tv.core.p2p.AceLiveDescriptorParser
import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSource
import com.iptv.tv.core.p2p.P2pSourceParser

internal enum class EngineStreamRoute {
    EMBEDDED_BITTORRENT,
    ACE_CONTENT_ID,
    ACE_LIVE_INFOHASH,
    ACE_LIVE_COMPATIBILITY,
    EXTERNAL_COMPATIBILITY
}

/**
 * Keeps player-facing source routing independent from either P2P implementation.
 *
 * Legacy Torrent TV playlists frequently encode Ace descriptors as loopback HTTP URLs such as
 * `http://127.0.0.1:6878/ace/getstream?id=...`. P2pSourceParser normalizes those descriptor-shaped
 * URLs before this router sees their transport identity: gateway `infohash` values identify direct
 * Ace Live swarms, while `id`/`content_id` values enter the Ace transport-metadata boundary. Bare
 * infohashes outside the legacy gateway remain standard BitTorrent. The original loopback URL is
 * never treated as a required player endpoint.
 *
 * `.acelive` transport files remain separate from standard BitTorrent because Ace Live uses a
 * distinct live piece/chunk protocol. Unknown legacy descriptors retain an external compatibility
 * path while the autonomous Ace metadata/live bootstrap is completed.
 */
internal object EngineStreamRouting {
    fun route(rawSource: String): EngineStreamRoute {
        val normalized = rawSource.trim()
        if (AceLiveDescriptorParser.parse(normalized) != null) {
            return EngineStreamRoute.ACE_LIVE_COMPATIBILITY
        }
        if (P2pSourceParser.parseAceLiveInfoHash(normalized) != null) {
            return EngineStreamRoute.ACE_LIVE_INFOHASH
        }

        return when (val parsed = P2pSourceParser.parse(normalized)) {
            is P2pResult.Success -> when (parsed.data) {
                is P2pSource.AceContentId -> EngineStreamRoute.ACE_CONTENT_ID
                else -> EngineStreamRoute.EMBEDDED_BITTORRENT
            }
            is P2pResult.Error -> EngineStreamRoute.EXTERNAL_COMPATIBILITY
        }
    }
}
