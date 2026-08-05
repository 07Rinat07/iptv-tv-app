package com.iptv.tv.core.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceStreamDescriptorParserTest {
    private val contentId = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun parsesAceStreamSchemeAsContentId() {
        val result = AceStreamDescriptorParser.parse("acestream://$contentId")

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
        assertEquals(mapOf("id" to contentId), AceStreamDescriptorParser.toEngineRequest(result))
    }

    @Test
    fun parsesLegacyAceSchemeAsContentId() {
        val result = AceStreamDescriptorParser.parse("ace://$contentId")

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
    }

    @Test
    fun extractsContentIdFromLocalEngineUrl() {
        val result = AceStreamDescriptorParser.parse(
            "http://127.0.0.1:6878/ace/getstream?id=$contentId"
        )

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
    }

    @Test
    fun keepsMagnetAndTransportFiles() {
        val magnet = "magnet:?xt=urn:btih:$contentId"
        val magnetResult = AceStreamDescriptorParser.parse(magnet)
        val torrentResult = AceStreamDescriptorParser.parse("https://example.org/live.torrent")
        val aceLiveResult = AceStreamDescriptorParser.parse("https://example.org/live.acelive")

        assertEquals(AceStreamDescriptor.Magnet(magnet, magnet), magnetResult)
        assertTrue(torrentResult is AceStreamDescriptor.TransportFile)
        assertTrue(aceLiveResult is AceStreamDescriptor.TransportFile)
    }

    @Test
    fun leavesRegularIptvUrlDirect() {
        val url = "https://example.org/channel/master.m3u8"
        val result = AceStreamDescriptorParser.parse(url)

        assertEquals(AceStreamDescriptor.Direct(url, url), result)
        assertTrue(AceStreamDescriptorParser.toEngineRequest(result).isEmpty())
    }
}
