package com.iptv.tv.core.parser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun parsesWhenHeaderMissingButEntriesLookValid() {
        val parser = M3uParser()
        val raw = """
            #EXTINF:-1,No Header
            https://example.com/stream
        """.trimIndent()

        val result = parser.parse(playlistId = 1, raw = raw)
        assertTrue(result is ParseResult.Valid)
        val valid = result as ParseResult.Valid
        assertEquals(1, valid.channels.size)
        assertTrue(valid.warnings.any { it.contains("auto-added", ignoreCase = true) })
    }

    @Test
    fun failsWhenHeaderMissingAndNoPlaylistStructure() {
        val parser = M3uParser()
        val raw = "plain text without extinf and stream url"

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

    @Test
    fun parsesQuotedUnquotedAndMultipleEpgUrls() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U url-tvg='https://epg.example/a.xml,https://epg.example/b.xml' x-tvg-url=https://epg.example/c.xml
            #EXTINF:-1 tvg-id="id1",Channel
            https://example.com/live
        """.trimIndent()

        val result = parser.parse(playlistId = 1, raw = raw) as ParseResult.Valid

        assertEquals(
            listOf(
                "https://epg.example/a.xml",
                "https://epg.example/b.xml",
                "https://epg.example/c.xml"
            ),
            result.epgUrls
        )
    }

    @Test
    fun preservesExplicitCatchUpMetadataWithoutInventingItForLiveOnlyChannels() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-id="archive-news" catchup="append" catchup-days="7" catchup-source="?utc=${'$'}{start}&lutc=${'$'}{timestamp}",Archive News
            https://example.com/archive-news.m3u8
            #EXTINF:-1 tvg-id="live-news",Live News
            https://example.com/live-news.m3u8
        """.trimIndent()

        val result = parser.parse(playlistId = 9, raw = raw) as ParseResult.Valid

        assertEquals(
            ChannelCatchUpMetadata(
                mode = "append",
                days = 7,
                sourceTemplate = "?utc=${'$'}{start}&lutc=${'$'}{timestamp}"
            ),
            result.catchUpByChannelOrderIndex[0]
        )
        assertFalse(result.catchUpByChannelOrderIndex.containsKey(1))
    }

    @Test
    fun preservesSingleQuotedAndUnquotedCatchUpAttributes() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 catchup='default' catchup-days=3 catchup-source=?start=${'$'}{start},Archive
            https://example.com/archive.m3u8
        """.trimIndent()

        val result = parser.parse(playlistId = 2, raw = raw) as ParseResult.Valid

        assertEquals(
            ChannelCatchUpMetadata(
                mode = "default",
                days = 3,
                sourceTemplate = "?start=${'$'}{start}"
            ),
            result.catchUpByChannelOrderIndex[0]
        )
    }

    @Test
    fun malformedCatchUpDaysDoesNotInventArchiveRange() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 catchup="default" catchup-days="seven",Archive
            https://example.com/archive.m3u8
        """.trimIndent()

        val result = parser.parse(playlistId = 2, raw = raw) as ParseResult.Valid
        val metadata = result.catchUpByChannelOrderIndex[0]

        assertEquals("default", metadata?.mode)
        assertNull(metadata?.days)
        assertTrue(metadata?.daysDeclared == true)
        assertNull(metadata?.sourceTemplate)
    }

    @Test
    fun catchUpTextInsideLogoOrTitleDoesNotInventArchiveCapability() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://img.test/logo?catchup=default,still-logo",Live catchup=append
            https://example.com/live.m3u8
        """.trimIndent()

        val result = parser.parse(playlistId = 3, raw = raw) as ParseResult.Valid

        assertEquals("Live catchup=append", result.channels.single().name)
        assertEquals(
            "https://img.test/logo?catchup=default,still-logo",
            result.channels.single().logo
        )
        assertTrue(result.catchUpByChannelOrderIndex.isEmpty())
    }

    @Test
    fun standaloneCatchUpAttributeRemainsVisibleBesideCatchUpTextInOtherAttributes() {
        val parser = M3uParser()
        val raw = """
            #EXTM3U
            #EXTINF:-1 tvg-logo="https://img.test/logo?catchup=wrong" catchup="append" catchup-days="2",Archive
            https://example.com/archive.m3u8
        """.trimIndent()

        val result = parser.parse(playlistId = 4, raw = raw) as ParseResult.Valid

        assertEquals(
            ChannelCatchUpMetadata(mode = "append", days = 2, sourceTemplate = null),
            result.catchUpByChannelOrderIndex[0]
        )
    }
}
