package com.iptv.tv.core.engine.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveSwarmMetadataTest {
    @Test
    fun `live metadata exposes swarm infohash without exposing BTIH`() {
        val metadata = metadata(
            infoHash = "00112233445566778899aabbccddeeff00112233",
            mediaType = "live",
            transportType = "live"
        )

        assertEquals("00112233445566778899aabbccddeeff00112233", metadata.liveSwarmInfoHash)
        assertNull(metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `live swarm infohash falls back to live file metadata`() {
        val metadata = metadata(
            infoHash = null,
            mediaType = null,
            transportType = null,
            files = listOf(
                file(
                    infoHash = "ffeeddccbbaa99887766554433221100ffeeddcc",
                    mediaType = "live",
                    transportType = "live"
                )
            )
        )

        assertEquals("ffeeddccbbaa99887766554433221100ffeeddcc", metadata.liveSwarmInfoHash)
        assertNull(metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `mixed metadata selects hash belonging to live entry`() {
        val metadata = metadata(
            infoHash = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            mediaType = "vod",
            transportType = null,
            files = listOf(
                file(
                    infoHash = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                    mediaType = "vod",
                    transportType = "bt"
                ),
                file(
                    infoHash = "cccccccccccccccccccccccccccccccccccccccc",
                    mediaType = "live",
                    transportType = "live"
                )
            )
        )

        assertEquals("cccccccccccccccccccccccccccccccccccccccc", metadata.liveSwarmInfoHash)
        assertNull(metadata.embeddedBitTorrentInfoHash)
    }

    @Test
    fun `ordinary bittorrent keeps BTIH path and has no live swarm identity`() {
        val metadata = metadata(
            infoHash = "1234567890abcdef1234567890abcdef12345678",
            mediaType = "vod",
            transportType = "bt"
        )

        assertNull(metadata.liveSwarmInfoHash)
        assertEquals("1234567890abcdef1234567890abcdef12345678", metadata.embeddedBitTorrentInfoHash)
    }

    private fun metadata(
        infoHash: String?,
        mediaType: String?,
        transportType: String?,
        files: List<AceTransportFile> = emptyList()
    ): AceTransportMetadata = AceTransportMetadata(
        infoHash = infoHash,
        mediaType = mediaType,
        transportType = transportType,
        name = null,
        files = files,
        transportFileData = null,
        transportFileCacheKey = null,
        wrapperData = null
    )

    private fun file(
        infoHash: String,
        mediaType: String,
        transportType: String
    ): AceTransportFile = AceTransportFile(
        index = 0,
        infoHash = infoHash,
        mediaType = mediaType,
        transportType = transportType,
        filename = null,
        mime = null,
        size = null
    )
}
