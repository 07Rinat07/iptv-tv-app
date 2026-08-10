package com.iptv.tv.core.data.repository

import com.iptv.tv.core.p2p.AceLiveDescriptorParser
import com.iptv.tv.core.p2p.P2pResult
import com.iptv.tv.core.p2p.P2pSource
import com.iptv.tv.core.p2p.P2pSourceParser
import java.net.URI
import java.util.Locale

internal enum class EngineStreamRoute {
    LOCAL_ACE_GATEWAY,
    EMBEDDED_BITTORRENT,
    ACE_LIVE_COMPATIBILITY,
    EXTERNAL_ACE
}

/**
 * Keeps player-facing source routing independent from either P2P implementation.
 *
 * Legacy playlists may already contain a loopback Ace HTTP gateway URL such as
 * `http://127.0.0.1:6878/ace/getstream?id=...`. That URL is already addressed to the Engine on
 * the current device, so preserving it avoids unnecessarily converting it back to a content id and
 * forcing service-binding/API discovery before playback.
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
        if (isLocalAceGatewayUrl(normalized)) {
            return EngineStreamRoute.LOCAL_ACE_GATEWAY
        }
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

    private fun isLocalAceGatewayUrl(raw: String): Boolean {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return false
        if (!uri.scheme.equals("http", ignoreCase = true) &&
            !uri.scheme.equals("https", ignoreCase = true)
        ) {
            return false
        }

        val host = uri.host?.lowercase(Locale.ROOT) ?: return false
        val loopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        return loopback && uri.path.orEmpty().startsWith("/ace/", ignoreCase = true)
    }
}
