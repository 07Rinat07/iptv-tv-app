package com.iptv.tv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uParserTest {
    @Test
    fun parsesValidPlaylist() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-id="id1" group-title="News",News 1
            https://example.com/stream1
        """.trimIndent()

        val result = parser.parse(playlistId = 1, raw = raw)

        assertTrue(result is ParseResult.Valid)
        val channels = (result as ParseResult.Valid).channels
        assertEquals(1, channels.size)
        assertEquals("News 1", channels.first().name)
    }

    @Test
    fun failsWhenHeaderMissing() {
        val parser = M3uParser()
        val raw = """
            #EXTINF:-1,No Header
            https://example.com/stream
        """.trimIndent()

        val result = parser.parse(playlistId = 1, raw = raw)
        assertTrue(result is ParseResult.Invalid)
    }

    @Test
    fun returnsWarningsForBrokenEntries() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1,Broken
            not-a-url
            #EXTINF:-1,Valid
            http://example.com/live
        """.trimIndent()

        val result = parser.parse(playlistId = 1, raw = raw)
        assertTrue(result is ParseResult.Valid)
        val valid = result as ParseResult.Valid
        assertEquals(1, valid.channels.size)
        assertTrue(valid.warnings.isNotEmpty())
    }

    @Test
    fun parsesLargePlaylistOverEightThousandChannels() {
        val parser = M3uParser()
        val channels = 8_100
        val payload = buildString {
            appendLine("#EXTM3U")
            repeat(channels) { index ->
                appendLine("#EXTINF:-1 tvg-id=\"id$index\" group-title=\"Group${index % 15}\",Channel $index")
                appendLine("https://example.com/live/$index.m3u8")
            }
        }

        val result = parser.parse(playlistId = 7, raw = payload)
        assertTrue(result is ParseResult.Valid)
        val valid = result as ParseResult.Valid
        assertEquals(channels, valid.channels.size)
    }
}
