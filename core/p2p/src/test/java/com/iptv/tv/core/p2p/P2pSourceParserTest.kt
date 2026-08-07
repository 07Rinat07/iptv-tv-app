package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pSourceParserTest {
    @Test
    fun parsesMagnet() {
        val source = P2pSourceParser.parse("magnet:?xt=urn:btih:0123456789012345678901234567890123456789")
        assertTrue(source is P2pResult.Success)
        assertTrue((source as P2pResult.Success).data is P2pSource.Magnet)
    }

    @Test
    fun parsesSha1InfoHashAndBuildsMagnet() {
        val hash = "0123456789abcdef0123456789abcdef01234567"
        val source = P2pSourceParser.parse(hash)
        assertTrue(source is P2pResult.Success)
        val data = (source as P2pResult.Success).data
        assertEquals("magnet:?xt=urn:btih:$hash", P2pSourceParser.toMagnetUri(data))
    }

    @Test
    fun parsesAceContentIdSeparatelyFromBitTorrent() {
        val source = P2pSourceParser.parse("acestream://abcdef123456")
        assertTrue(source is P2pResult.Success)
        assertEquals(
            P2pSource.AceContentId("abcdef123456"),
            (source as P2pResult.Success).data
        )
    }

    @Test
    fun acceptsTorrentUrlWithQuery() {
        val source = P2pSourceParser.parse("https://example.test/download?id=1&file=movie.torrent")
        assertTrue(source is P2pResult.Success)
        assertTrue((source as P2pResult.Success).data is P2pSource.TorrentUrl)
    }

    @Test
    fun rejectsOrdinaryHttpStream() {
        val source = P2pSourceParser.parse("https://example.test/live/channel.m3u8")
        assertTrue(source is P2pResult.Error)
    }
}
