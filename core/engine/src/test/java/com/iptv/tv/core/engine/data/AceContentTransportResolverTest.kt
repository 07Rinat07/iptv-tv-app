package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.engine.api.EngineStreamApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentTransportResolverTest {
    private val resolver = ExternalAceContentTransportResolver(
        object : AceContentMetadataProvider {
            override suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata> =
                AppResult.Error("not used by classification tests")
        }
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
    fun classify_nonLiveBitTorrentTransportFile_isEmbeddedCandidate() {
        val payload = "ZHVtbXktdG9ycmVudA=="
        val metadata = metadata(
            infoHash = null,
            mediaType = "vod",
            transportType = "bt",
            transportFileData = payload
        )

        val result = resolver.classify(metadata)

        assertTrue(result is AceContentTransportResolution.EmbeddedTorrentFile)
        assertEquals(
            payload,
            (result as AceContentTransportResolution.EmbeddedTorrentFile).transportFileDataBase64
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
            transportType = "hls",
            transportFileData = "ZHVtbXk="
        )

        val result = resolver.classify(metadata)

        assertTrue(result is AceContentTransportResolution.Unsupported)
        assertTrue(
            (result as AceContentTransportResolution.Unsupported)
                .reason
                .contains("hls")
        )
    }

    @Test
    fun loopbackRecovery_resolvesWhenBoundServiceIsUnavailableButHttpEngineIsRunning() = runTest {
        val contentId = "11223344556677889900aabbccddeeff00112233"
        val infoHash = "0a4848271c91ce2d8965ce416267c25047dc8141"
        val api = FakeApi(
            statusPayload = mapOf(
                "response" to mapOf(
                    "status" to "ok",
                    "peers" to 3,
                    "speed" to 1200
                )
            ),
            resolvePayload = mapOf(
                "result" to mapOf(
                    "infohash" to infoHash,
                    "transport_type" to "bt",
                    "type" to "vod"
                )
            )
        )
        val client = EngineStreamClient(api)
        val metadataProvider = ChainedAceContentMetadataProvider(
            listOf(ExternalEngineAceContentMetadataProvider(client))
        )
        val external = ExternalAceContentTransportResolver(metadataProvider)
        val recovering = LoopbackFirstAceContentTransportResolver(
            client = client,
            delegate = external
        )

        val result = recovering.resolve("acestream://$contentId")

        assertTrue(result is AppResult.Success)
        val resolution = (result as AppResult.Success).data
        assertTrue(resolution is AceContentTransportResolution.EmbeddedBitTorrent)
        assertEquals(
            infoHash,
            (resolution as AceContentTransportResolution.EmbeddedBitTorrent).infoHash
        )
        assertEquals("http://127.0.0.1:6878/webui/api/service", api.lastStatusUrl)
        assertEquals("http://127.0.0.1:6878/server/api", api.lastResolveUrl)

        val runtime = client.observeRuntimeDiagnostics().value
        assertEquals(AceRuntimeStage.METADATA_READY, runtime.stage)
        assertEquals("loopback_compatibility", runtime.route)
        assertEquals("loopback_http", runtime.enginePackage)
        assertEquals("http://127.0.0.1:6878", runtime.endpoint)
        assertEquals("primary_metadata_failed", runtime.fallbackReason)
        assertEquals(null, runtime.failureCode)
        assertTrue(!runtime.toSummary().contains(contentId, ignoreCase = true))
    }

    private fun metadata(
        infoHash: String?,
        mediaType: String?,
        transportType: String?,
        transportFileData: String? = null
    ) = AceTransportMetadata(
        infoHash = infoHash,
        mediaType = mediaType,
        transportType = transportType,
        name = "test",
        files = emptyList(),
        transportFileData = transportFileData,
        transportFileCacheKey = null,
        wrapperData = null
    )

    private class FakeApi(
        private val statusPayload: Map<String, Any?>,
        private val resolvePayload: Map<String, Any?>
    ) : EngineStreamApi {
        var lastStatusUrl: String? = null
        var lastResolveUrl: String? = null

        override suspend fun status(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> {
            lastStatusUrl = url
            return statusPayload
        }

        override suspend fun resolve(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> {
            lastResolveUrl = url
            return resolvePayload
        }
    }
}
