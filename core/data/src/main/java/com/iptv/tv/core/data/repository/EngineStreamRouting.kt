package com.iptv.tv.core.data.repository

import com.iptv.tv.core.p2p.AceLiveDescriptorParser
import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSource
import com.iptv.tv.core.p2p.P2pSourceParser

internal enum class EngineStreamRoute {
    EMBEDDED_BITTORRENT,
    ACE_LIVE_COMPATIBILITY,
    EXTERNAL_ACE
}

/**
 * Keeps player-facing source routing independent from either P2P implementation.
 *
 * `.acelive` transport files are detected explicitly and kept off standard libtorrent because
 * Ace Live uses a distinct live piece/chunk protocol. Ace content ids stay on the official Ace
 * integration until their transport metadata proves they are ordinary non-live BitTorrent.
 * Ace descriptors that explicitly carry a valid infohash are safe to route to embedded libtorrent.
 * Everything unknown falls back to the legacy external path for compatibility.
 */
internal object EngineStreamRouting {
    fun route(rawSource: String): EngineStreamRoute {
        val normalized = rawSource.trim()
        if (AceLiveDescriptorParser.parse(normalized) != null) {
            return EngineStreamRoute.ACE_LIVE_COMPATIBILITY
        }

        return when (val parsed = P2pSourceParser.parse(normalized)) {
            is P2pResult.Success -> when (parsed.data) {
                is P2pSource.AceContentId -> EngineStreamRoute.EXTERNAL_ACE
                else -> EngineStreamRoute.EMBEDDED_BITTORRENT
            }
            is P2pResult.Error -> EngineStreamRoute.EXTERNAL_ACE
        }
    }
}
