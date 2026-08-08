package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class AceLiveDescriptorTest {
    @Test
    fun parsesDirectHttpAceLiveUrl() {
        val source = "https://cdn.example/live/channel.acelive?token=abc"

        val parsed = AceLiveDescriptorParser.parse(source)

        assertNotNull(parsed)
        assertEquals(source, parsed?.original)
        assertEquals(source, parsed?.transport)
    }

    @Test
    fun parsesEncodedAceUrlDescriptor() {
        val descriptor =
            "acestream:?url=https%3A%2F%2Fcdn.example%2Flive%2Fchannel.acelive%3Ftoken%3Dabc"

        val parsed = AceLiveDescriptorParser.parse(descriptor)

        assertNotNull(parsed)
        assertEquals("https://cdn.example/live/channel.acelive?token=abc", parsed?.transport)
    }

    @Test
    fun parsesAceDataDescriptorForLocalAceLiveFile() {
        val descriptor = "acestream:?data=file%3A%2F%2F%2Fsdcard%2Fchannel.acelive"

        val parsed = AceLiveDescriptorParser.parse(descriptor)

        assertNotNull(parsed)
        assertEquals("file:///sdcard/channel.acelive", parsed?.transport)
    }

    @Test
    fun ignoresOrdinaryTorrentAndHttpStream() {
        assertNull(AceLiveDescriptorParser.parse("https://example.test/channel.torrent"))
        assertNull(AceLiveDescriptorParser.parse("https://example.test/channel.m3u8"))
        assertNull(
            AceLiveDescriptorParser.parse(
                "acestream:?url=https%3A%2F%2Fexample.test%2Fchannel.torrent"
            )
        )
    }
}
