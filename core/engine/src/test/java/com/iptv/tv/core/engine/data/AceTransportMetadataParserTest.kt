package com.iptv.tv.core.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceTransportMetadataParserTest {
    @Test
    fun `parses official full metadata response`() {
        val infoHash = "0A4848271C91CE2D8965CE416267C25047DC8141"
        val metadata = AceTransportMetadataParser.parse(
            mapOf(
                "result" to mapOf(
                    "transport_file_data" to "BASE64_TRANSPORT",
                    "transport_file_cache_key" to "cache-key",
                    "infohash" to infoHash,
                    "name" to "Video",
                    "files" to listOf(
                        mapOf(
                            "infohash" to infoHash,
                            "type" to "vod",
                            "filename" to "video.ts",
                            "mime" to "video/mp2t",
                            "transport_type" to "bt",
                            "index" to 0,
                            "size" to 12_345L
                        )
                    ),
                    "wrapper_data" to mapOf(
                        "type" to "json",
                        "mime" to "application/json",
                        "data" to "{}"
                    )
                )
            )
        )

        assertEquals(infoHash.lowercase(), metadata.infoHash)
        assertEquals("vod", metadata.mediaType)
        assertEquals("bt", metadata.transportType)
        assertEquals("Video", metadata.name)
        assertEquals("BASE64_TRANSPORT", metadata.transportFileData)
        assertEquals("cache-key", metadata.transportFileCacheKey)
        assertEquals(1, metadata.files.size)
        assertEquals("video.ts", metadata.files.single().filename)
        assertEquals(12_345L, metadata.files.single().size)
        assertEquals("json", metadata.wrapperData?.type)
        assertEquals("application/json", metadata.wrapperData?.mime)
        assertFalse(metadata.isLive)
        assertEquals(infoHash.lowercase(), metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `uses file metadata when root transport fields are absent`() {
        val infoHash = "0a4848271c91ce2d8965ce416267c25047dc8141"
        val metadata = AceTransportMetadataParser.parse(
            mapOf(
                "result" to mapOf(
                    "files" to listOf(
                        mapOf(
                            "infohash" to infoHash,
                            "type" to "vod",
                            "transport_type" to "bt",
                            "index" to "2",
                            "size" to "9000"
                        )
                    )
                )
            )
        )

        assertEquals("vod", metadata.mediaType)
        assertEquals("bt", metadata.transportType)
        assertEquals(2, metadata.files.single().index)
        assertEquals(9_000L, metadata.files.single().size)
        assertEquals(infoHash, metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `live transport never exposes embedded BitTorrent hash`() {
        val infoHash = "0a4848271c91ce2d8965ce416267c25047dc8141"
        val metadata = AceTransportMetadataParser.parse(
            mapOf(
                "result" to mapOf(
                    "infohash" to infoHash,
                    "files" to listOf(
                        mapOf(
                            "infohash" to infoHash,
                            "type" to "live",
                            "transport_type" to "bt"
                        )
                    )
                )
            )
        )

        assertTrue(metadata.isLive)
        assertNull(metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `non BitTorrent transport never exposes embedded hash`() {
        val metadata = AceTransportMetadataParser.parse(
            mapOf(
                "result" to mapOf(
                    "files" to listOf(
                        mapOf(
                            "infohash" to "0a4848271c91ce2d8965ce416267c25047dc8141",
                            "type" to "vod",
                            "transport_type" to "hls"
                        )
                    )
                )
            )
        )

        assertEquals("hls", metadata.transportType)
        assertNull(metadata.embeddedBitTorrentInfoHash)
    }
}
