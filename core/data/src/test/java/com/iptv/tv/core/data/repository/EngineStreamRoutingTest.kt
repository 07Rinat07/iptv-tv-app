package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class EngineStreamRoutingTest {
    @Test
    fun magnetUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route("magnet:?xt=urn:btih:0123456789012345678901234567890123456789")
        )
    }

    @Test
    fun bareSha1InfoHashUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route("0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun torrentHttpUrlUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route("https://example.org/media/channel.torrent?token=abc")
        )
    }

    @Test
    fun legacyLoopbackAceGetStreamUsesLocalGatewayRoute() {
        assertEquals(
            EngineStreamRoute.LOCAL_ACE_GATEWAY,
            EngineStreamRouting.route(
                "http://127.0.0.1:6878/ace/getstream?id=0123456789abcdef0123456789abcdef01234567"
            )
        )
    }

    @Test
    fun localhostAceGatewayUsesLocalGatewayRoute() {
        assertEquals(
            EngineStreamRoute.LOCAL_ACE_GATEWAY,
            EngineStreamRouting.route(
                "http://localhost:6878/ace/getstream?id=0123456789abcdef0123456789abcdef01234567"
            )
        )
    }

    @Test
    fun remoteAceLikeUrlDoesNotUseLocalGatewayRoute() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_ACE,
            EngineStreamRouting.route(
                "https://example.org/ace/getstream?id=0123456789abcdef0123456789abcdef01234567"
            )
        )
    }

    @Test
    fun directAceLiveUrlUsesCompatibilityRoute() {
        assertEquals(
            EngineStreamRoute.ACE_LIVE_COMPATIBILITY,
            EngineStreamRouting.route("https://example.org/live/channel.acelive?token=abc")
        )
    }

    @Test
    fun encodedAceLiveDescriptorUsesCompatibilityRoute() {
        assertEquals(
            EngineStreamRoute.ACE_LIVE_COMPATIBILITY,
            EngineStreamRouting.route(
                "acestream:?url=https%3A%2F%2Fexample.org%2Flive%2Fchannel.acelive"
            )
        )
    }

    @Test
    fun localTorrentUriUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route("content://com.example.provider/torrents/42")
        )
    }

    @Test
    fun aceDescriptorWithExplicitInfoHashUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route(
                "acestream:?infohash=0123456789abcdef0123456789abcdef01234567"
            )
        )
    }

    @Test
    fun acestreamContentIdStaysExternalEvenWhenItLooksLikeInfoHash() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_ACE,
            EngineStreamRouting.route("acestream://0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun legacyAceSchemeStaysExternal() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_ACE,
            EngineStreamRouting.route("ace://0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun unknownDescriptorKeepsLegacyCompatibilityPath() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_ACE,
            EngineStreamRouting.route("legacy-engine-descriptor")
        )
    }

    @Test
    fun normalHttpStreamIsNotMisclassifiedAsTorrent() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_ACE,
            EngineStreamRouting.route("https://example.org/live/channel.m3u8")
        )
    }
}
