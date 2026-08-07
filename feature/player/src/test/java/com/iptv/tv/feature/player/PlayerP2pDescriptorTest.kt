package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerP2pDescriptorTest {
    @Test
    fun detectsTorrentUrlWithQueryAndKeepsToken() {
        val source = "https://example.org/live/channel.torrent?token=abc123"
        assertEquals(source, PlayerP2pDescriptor.detect(source))
    }

    @Test
    fun detectsTorrentUrlWithFragment() {
        val source = "https://example.org/live/channel.torrent#mirror"
        assertEquals(source, PlayerP2pDescriptor.detect(source))
    }

    @Test
    fun detectsTorrentEndpointUsingTorrentQueryParameter() {
        val source = "https://example.org/download?torrent=channel"
        assertEquals(source, PlayerP2pDescriptor.detect(source))
    }

    @Test
    fun detectsEncodedNestedTorrentUrl() {
        val source = "https://example.org/open?url=https%3A%2F%2Fcdn.example.org%2Fchannel.torrent%3Ftoken%3Dx"
        assertEquals(
            "https://cdn.example.org/channel.torrent?token=x",
            PlayerP2pDescriptor.detect(source)
        )
    }

    @Test
    fun normalizesInfoHashAndLegacyAceDescriptors() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals("magnet:?xt=urn:btih:$hash", PlayerP2pDescriptor.detect("infohash:$hash"))
        assertEquals("magnet:?xt=urn:btih:$hash", PlayerP2pDescriptor.detect(hash))
        assertEquals("acestream://$hash", PlayerP2pDescriptor.detect("ace://$hash"))
    }

    @Test
    fun directIptvUrlIsNotP2p() {
        assertNull(PlayerP2pDescriptor.detect("https://example.org/live/channel.m3u8?token=abc"))
    }

    @Test
    fun describesEmbeddedBitTorrentAndExternalAceSeparately() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            "BitTorrent поток (встроенный P2P)",
            PlayerP2pDescriptor.describe("magnet:?xt=urn:btih:$hash")
        )
        assertEquals(
            "Ace Stream поток (внешний Engine)",
            PlayerP2pDescriptor.describe("acestream://$hash")
        )
        assertEquals(
            "IPTV поток (прямой URL)",
            PlayerP2pDescriptor.describe("https://example.org/live/channel.m3u8")
        )
    }
}
