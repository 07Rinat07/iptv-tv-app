package com.iptv.tv.core.engine.acestream

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceStreamDescriptorParserTest {
    @Test
    fun parse_contentIdFromAceStreamScheme() {
        val id = "11223344556677889900AABBCCDDEEFF00112233"
        assertEquals(
            AceStreamDescriptor.ContentId(id),
            AceStreamDescriptorParser.parse("acestream://$id")
        )
    }

    @Test
    fun parse_plainContentId() {
        val id = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        assertEquals(
            AceStreamDescriptor.ContentId(id),
            AceStreamDescriptorParser.parse(id)
        )
    }

    @Test
    fun parse_aceliveUrl() {
        val source = "https://example.org/live/channel.acelive?token=1"
        assertEquals(
            AceStreamDescriptor.TransportUrl(source),
            AceStreamDescriptorParser.parse(source)
        )
    }

    @Test
    fun parse_regularHlsIsNotAceStream() {
        assertNull(AceStreamDescriptorParser.parse("https://example.org/live.m3u8"))
    }

    @Test
    fun buildPlaybackUrl_usesContentIdParameter() {
        val id = "11223344556677889900AABBCCDDEEFF00112233"
        val url = AceStreamDescriptorParser.buildPlaybackUrl(
            endpoint = "http://127.0.0.1:6878/",
            descriptor = AceStreamDescriptor.ContentId(id)
        )
        assertEquals("http://127.0.0.1:6878/ace/getstream?id=$id", url)
    }

    @Test
    fun buildPlaybackUrl_encodesTransportUrl() {
        val url = AceStreamDescriptorParser.buildPlaybackUrl(
            endpoint = "http://127.0.0.1:6878",
            descriptor = AceStreamDescriptor.TransportUrl("magnet:?xt=urn:btih:ABC&dn=Test")
        )
        assertTrue(url.startsWith("http://127.0.0.1:6878/ace/getstream?url="))
        assertTrue(url.contains("magnet%3A%3Fxt%3Durn%3Abtih%3AABC%26dn%3DTest"))
    }
}
