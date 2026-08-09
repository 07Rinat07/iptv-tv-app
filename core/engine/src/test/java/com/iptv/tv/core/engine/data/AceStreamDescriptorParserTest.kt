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
        assertEquals(
            mapOf("content_id" to contentId),
            AceStreamDescriptorParser.toEngineRequest(result)
        )
    }

    @Test
    fun parsesOfficialAceQueryAsContentId() {
        val result = AceStreamDescriptorParser.parse("acestream:?content_id=$contentId")

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
        assertEquals(
            mapOf("content_id" to contentId),
            AceStreamDescriptorParser.toEngineRequest(result)
        )
    }

    @Test
    fun parsesEncodedAceQueryMagnetAndUrl() {
        val magnet = "magnet:?xt=urn:btih:$contentId"
        val magnetResult = AceStreamDescriptorParser.parse(
            "acestream:?magnet=magnet%3A%3Fxt%3Durn%3Abtih%3A$contentId"
        )
        val urlResult = AceStreamDescriptorParser.parse(
            "acestream:?url=https%3A%2F%2Fexample.org%2Flive.acelive"
        )

        assertEquals(AceStreamDescriptor.Magnet(magnetResult.original, magnet), magnetResult)
        assertEquals(
            mapOf("magnet" to magnet),
            AceStreamDescriptorParser.toEngineRequest(magnetResult)
        )
        assertTrue(urlResult is AceStreamDescriptor.TransportFile)
        assertEquals(
            mapOf("url" to "https://example.org/live.acelive"),
            AceStreamDescriptorParser.toEngineRequest(urlResult)
        )
    }

    @Test
    fun parsesLegacyAceSchemeAsContentId() {
        val result = AceStreamDescriptorParser.parse("ace://$contentId")

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
        assertEquals(
            mapOf("content_id" to contentId),
            AceStreamDescriptorParser.toEngineRequest(result)
        )
    }

    @Test
    fun normalizesInfoHashToMagnet() {
        val uppercaseInfoHash = contentId.uppercase()
        val source = "infohash:$uppercaseInfoHash"
        val expectedMagnet = "magnet:?xt=urn:btih:$uppercaseInfoHash"

        val result = AceStreamDescriptorParser.parse(source)

        assertEquals(AceStreamDescriptor.Magnet(source, expectedMagnet), result)
        assertEquals(
            mapOf("magnet" to expectedMagnet),
            AceStreamDescriptorParser.toEngineRequest(result)
        )
    }

    @Test
    fun extractsContentIdFromLocalEngineUrl() {
        val result = AceStreamDescriptorParser.parse(
            "http://127.0.0.1:6878/ace/getstream?id=$contentId"
        )

        assertTrue(result is AceStreamDescriptor.ContentId)
        assertEquals(contentId, (result as AceStreamDescriptor.ContentId).value)
        assertEquals(
            mapOf("content_id" to contentId),
            AceStreamDescriptorParser.toEngineRequest(result)
        )
    }

    @Test
    fun keepsMagnetAndTransportFiles() {
        val magnet = "magnet:?xt=urn:btih:$contentId"
        val magnetResult = AceStreamDescriptorParser.parse(magnet)
        val torrentResult = AceStreamDescriptorParser.parse("https://example.org/live.torrent")
        val aceLiveResult = AceStreamDescriptorParser.parse("https://example.org/live.acelive")

        assertEquals(AceStreamDescriptor.Magnet(magnet, magnet), magnetResult)
        assertEquals(
            mapOf("magnet" to magnet),
            AceStreamDescriptorParser.toEngineRequest(magnetResult)
        )
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
