package com.iptv.tv.core.engine.data

import com.iptv.tv.core.engine.api.EngineStreamApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentTransportResolverTest {
    private val resolver = ExternalAceContentTransportResolver(
        EngineStreamClient(EmptyApi())
    )

    @Test
    fun classify_nonLiveBitTorrent_isEmbeddedCandidate() {
        val metadata = metadata(
            infoHash = "0a4848271c91ce2d8965ce416267c25047dc8141",
            mediaType = "vod",
            transportType = "bt"
        )

        val result = resolver.classify(metadata)

        assertTrue(result is AceContentTransportResolution.EmbeddedBitTorrent)
        assertEquals(
            "0a4848271c91ce2d8965ce416267c25047dc8141",
            (result as AceContentTransportResolution.EmbeddedBitTorrent).infoHash
        )
    }

    @Test
    fun classify_liveBitTorrent_neverLeaksIntoStandardLibtorrent() {
        val metadata = metadata(
            infoHash = "0a4848271c91ce2d8965ce416267c25047dc8141",
            mediaType = "live",
            transportType = "bt"
        )

        val result = resolver.classify(metadata)

        assertTrue(result is AceContentTransportResolution.AceLive)
    }

    @Test
    fun classify_nonBitTorrentTransport_isUnsupported() {
        val metadata = metadata(
            infoHash = null,
            mediaType = "vod",
            transportType = "hls"
        )

        val result = resolver.classify(metadata)

        assertTrue(result is AceContentTransportResolution.Unsupported)
        assertTrue(
            (result as AceContentTransportResolution.Unsupported)
                .reason
                .contains("hls")
        )
    }

    private fun metadata(
        infoHash: String?,
        mediaType: String?,
        transportType: String?
    ) = AceTransportMetadata(
        infoHash = infoHash,
        mediaType = mediaType,
        transportType = transportType,
        name = "test",
        files = emptyList(),
        transportFileData = null,
        transportFileCacheKey = null,
        wrapperData = null
    )

    private class EmptyApi : EngineStreamApi {
        override suspend fun status(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> = emptyMap()

        override suspend fun resolve(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> = emptyMap()
    }
}
