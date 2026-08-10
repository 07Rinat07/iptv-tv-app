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
    fun normalizesExplicitBase32InfoHash() {
        val hash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        assertEquals(
            "magnet:?xt=urn:btih:$hash",
            PlayerP2pDescriptor.detect("infohash:${hash.lowercase()}")
        )
    }

    @Test
    fun normalizesBareBase32InfoHash() {
        val hash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        assertEquals(
            "magnet:?xt=urn:btih:$hash",
            PlayerP2pDescriptor.detect(hash.lowercase())
        )
        assertEquals(
            "BitTorrent поток (встроенный P2P)",
            PlayerP2pDescriptor.describe(hash.lowercase())
        )
    }

    @Test
    fun normalizesStandaloneUrnBtihHexAndBase32() {
        val hex = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val base32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

        assertEquals(
            "magnet:?xt=urn:btih:${hex.lowercase()}",
            PlayerP2pDescriptor.detect("URN:BTIH:$hex")
        )
        assertEquals(
            "magnet:?xt=urn:btih:$base32",
            PlayerP2pDescriptor.detect("urn:btih:${base32.lowercase()}")
        )
        assertEquals(
            "BitTorrent поток (встроенный P2P)",
            PlayerP2pDescriptor.describe("urn:btih:${base32.lowercase()}")
        )
    }

    @Test
    fun normalizesUrnBtihInsideInfoHashQuery() {
        val hash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val source = "https://example.org/play?infohash=urn%3Abtih%3A${hash.lowercase()}"

        assertEquals(
            "magnet:?xt=urn:btih:$hash",
            PlayerP2pDescriptor.detect(source)
        )
    }

    @Test
    fun localAceGatewayContentIdNormalizesToAceDescriptor() {
        val contentId = "1111111111111111111111111111111111111111"
        val source = "http://127.0.0.1:6878/ace/getstream?id=$contentId"

        assertEquals("acestream://$contentId", PlayerP2pDescriptor.detect(source))
        assertEquals("Ace Stream поток (P2P/Ace)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun localhostAceGatewayContentIdNormalizesToAceDescriptor() {
        val contentId = "1111111111111111111111111111111111111111"
        val source = "http://localhost:6878/ace/getstream?id=$contentId"

        assertEquals("acestream://$contentId", PlayerP2pDescriptor.detect(source))
    }

    @Test
    fun localAceGatewayInfoHashNormalizesToEmbeddedMagnet() {
        val infoHash = "2222222222222222222222222222222222222222"
        val source = "http://127.0.0.1:6878/ace/getstream?infohash=$infoHash&pid=38900686757"

        assertEquals("magnet:?xt=urn:btih:$infoHash", PlayerP2pDescriptor.detect(source))
        assertEquals("BitTorrent поток (встроенный P2P)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun remoteContentIdQueryRemainsAceDescriptorEvenWhenItLooksLikeSha1() {
        val contentId = "1111111111111111111111111111111111111111"
        val source = "https://example.org/play?id=$contentId"

        assertEquals("acestream://$contentId", PlayerP2pDescriptor.detect(source))
        assertEquals("Ace Stream поток (P2P/Ace)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun base32ShapedContentIdRemainsAce() {
        val contentId = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val source = "https://example.org/play?content_id=$contentId"

        assertEquals("acestream://$contentId", PlayerP2pDescriptor.detect(source))
        assertEquals("Ace Stream поток (P2P/Ace)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun explicitInfoHashQueryUsesEmbeddedBitTorrent() {
        val infoHash = "2222222222222222222222222222222222222222"
        val source = "https://example.org/play?infohash=$infoHash"

        assertEquals("magnet:?xt=urn:btih:$infoHash", PlayerP2pDescriptor.detect(source))
        assertEquals("BitTorrent поток (встроенный P2P)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun explicitBase32InfoHashQueryUsesEmbeddedBitTorrent() {
        val infoHash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val source = "https://example.org/play?infohash=${infoHash.lowercase()}"

        assertEquals("magnet:?xt=urn:btih:$infoHash", PlayerP2pDescriptor.detect(source))
        assertEquals("BitTorrent поток (встроенный P2P)", PlayerP2pDescriptor.describe(source))
    }

    @Test
    fun infoHashWinsWhenAceApiUrlContainsBothIdentifiersRegardlessOfOrder() {
        val contentId = "3333333333333333333333333333333333333333"
        val infoHash = "4444444444444444444444444444444444444444"
        val contentFirst = "https://example.org/play?content_id=$contentId&infohash=$infoHash"
        val infoHashFirst = "https://example.org/play?infohash=$infoHash&content_id=$contentId"
        val expected = "magnet:?xt=urn:btih:$infoHash"

        assertEquals(expected, PlayerP2pDescriptor.detect(contentFirst))
        assertEquals(expected, PlayerP2pDescriptor.detect(infoHashFirst))
    }

    @Test
    fun base32InfoHashStillWinsOverAceContentId() {
        val contentId = "CONTENTIDABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val infoHash = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val source = "https://example.org/play?content_id=$contentId&infohash=${infoHash.lowercase()}"

        assertEquals("magnet:?xt=urn:btih:$infoHash", PlayerP2pDescriptor.detect(source))
    }

    @Test
    fun directIptvUrlIsNotP2p() {
        assertNull(PlayerP2pDescriptor.detect("https://example.org/live/channel.m3u8?token=abc"))
    }

    @Test
    fun describesEmbeddedBitTorrentAndAceSeparately() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        assertEquals(
            "BitTorrent поток (встроенный P2P)",
            PlayerP2pDescriptor.describe("magnet:?xt=urn:btih:$hash")
        )
        assertEquals(
            "Ace Stream поток (P2P/Ace)",
            PlayerP2pDescriptor.describe("acestream://$hash")
        )
        assertEquals(
            "IPTV поток (прямой URL)",
            PlayerP2pDescriptor.describe("https://example.org/live/channel.m3u8")
        )
    }
}
