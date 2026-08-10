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
    fun legacyLoopbackAceContentIdUsesAceContentRoute() {
        assertEquals(
            EngineStreamRoute.ACE_CONTENT_ID,
            EngineStreamRouting.route(
                "http://127.0.0.1:6878/ace/getstream?id=50bc2f512793f1e745fb5bd5b5a6afca199c2d19"
            )
        )
    }

    @Test
    fun localhostAceContentIdUsesAceContentRoute() {
        assertEquals(
            EngineStreamRoute.ACE_CONTENT_ID,
            EngineStreamRouting.route(
                "http://localhost:6878/ace/getstream?id=50bc2f512793f1e745fb5bd5b5a6afca199c2d19"
            )
        )
    }

    @Test
    fun legacyLoopbackAceInfoHashUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route(
                "http://127.0.0.1:6878/ace/getstream?infohash=881ffab7e64f437d16d2ca4474c291a4a1111bd2"
            )
        )
    }

    @Test
    fun legacyLoopbackAceInfoHashWithPidUsesEmbeddedBitTorrent() {
        assertEquals(
            EngineStreamRoute.EMBEDDED_BITTORRENT,
            EngineStreamRouting.route(
                "http://127.0.0.1:6878/ace/getstream?infohash=cd8c7fcc7fb8c597d64b41429e0596887e097e54&pid=38900686757"
            )
        )
    }

    @Test
    fun remoteAceLikeUrlDoesNotPretendToBeLocalDescriptor() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_COMPATIBILITY,
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
    fun acestreamContentIdUsesAceContentRouteEvenWhenItLooksLikeInfoHash() {
        assertEquals(
            EngineStreamRoute.ACE_CONTENT_ID,
            EngineStreamRouting.route("acestream://0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun legacyAceSchemeUsesAceContentRoute() {
        assertEquals(
            EngineStreamRoute.ACE_CONTENT_ID,
            EngineStreamRouting.route("ace://0123456789abcdef0123456789abcdef01234567")
        )
    }

    @Test
    fun unknownDescriptorKeepsLegacyCompatibilityPath() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_COMPATIBILITY,
            EngineStreamRouting.route("legacy-engine-descriptor")
        )
    }

    @Test
    fun normalHttpStreamIsNotMisclassifiedAsTorrent() {
        assertEquals(
            EngineStreamRoute.EXTERNAL_COMPATIBILITY,
            EngineStreamRouting.route("https://example.org/live/channel.m3u8")
        )
    }
}
