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
 * Explicit Ace descriptors stay on the official Ace Engine integration. Sources that the
 * embedded parser can prove are BitTorrent metadata are routed to libtorrent. Everything
 * unknown falls back to the legacy external path to preserve compatibility with descriptors
 * understood by AceStreamDescriptorParser but not by the embedded parser.
 */
internal object EngineStreamRouting {
    fun route(rawSource: String): EngineStreamRoute {
        val source = rawSource.trim()
        if (
            source.startsWith("acestream://", ignoreCase = true) ||
            source.startsWith("ace://", ignoreCase = true)
        ) {
            return EngineStreamRoute.EXTERNAL_ACE
        }

        return when (val parsed = P2pSourceParser.parse(source)) {
            is P2pResult.Success -> when (parsed.data) {
                is P2pSource.AceContentId -> EngineStreamRoute.EXTERNAL_ACE
                else -> EngineStreamRoute.EMBEDDED_BITTORRENT
            }
            is P2pResult.Error -> EngineStreamRoute.EXTERNAL_ACE
        }
    }
}
