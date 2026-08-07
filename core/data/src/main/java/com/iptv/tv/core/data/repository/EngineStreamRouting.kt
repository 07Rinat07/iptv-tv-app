package com.iptv.tv.core.data.repository

import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSource
import com.iptv.tv.core.p2p.P2pSourceParser

internal enum class EngineStreamRoute {
    EMBEDDED_BITTORRENT,
    EXTERNAL_ACE
}

/**
 * Keeps player-facing source routing independent from either P2P implementation.
 *
 * Ace content ids stay on the official Ace Engine integration because a content id is not a
 * BitTorrent infohash. Ace descriptors that explicitly carry a valid infohash are safe to route
 * to the embedded libtorrent backend. Everything unknown falls back to the legacy external path
 * to preserve compatibility with descriptors understood only by AceStreamDescriptorParser.
 */
internal object EngineStreamRouting {
    fun route(rawSource: String): EngineStreamRoute {
        return when (val parsed = P2pSourceParser.parse(rawSource.trim())) {
            is P2pResult.Success -> when (parsed.data) {
                is P2pSource.AceContentId -> EngineStreamRoute.EXTERNAL_ACE
                else -> EngineStreamRoute.EMBEDDED_BITTORRENT
            }
            is P2pResult.Error -> EngineStreamRoute.EXTERNAL_ACE
        }
    }
}
