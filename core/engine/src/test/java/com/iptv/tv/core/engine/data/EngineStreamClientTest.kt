package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.engine.api.EngineStreamApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineStreamClientTest {

    @Test
    fun connect_parsesStatusAndMarksConnected() = runTest {
        val api = FakeApi(
            statusPayload = mapOf(
                "response" to mapOf(
                    "peers" to 9,
                    "speed" to 1500,
                    "status" to "ok"
                )
            )
        )
        val client = EngineStreamClient(api)

        val result = client.connect("http://127.0.0.1:6878")
        assertTrue(result is AppResult.Success)

        val status = client.observeStatus().value
        assertTrue(status.connected)
        assertEquals(9, status.peers)
        assertEquals(1500, status.speedKbps)
    }

    @Test
    fun resolve_nonTorrentReturnsInput() = runTest {
        val client = EngineStreamClient(FakeApi())
        val result = client.resolveStream("https://cdn.example/live.m3u8")

        assertTrue(result is AppResult.Success)
        assertEquals(
            "https://cdn.example/live.m3u8",
            (result as AppResult.Success).data
        )
    }

    @Test
    fun resolve_magnetUsesCurrentGetStreamControlApiAndPlaybackUrl() = runTest {
        val playbackUrl = "http://127.0.0.1:6878/ace/stream/resolved"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = playbackPayload(playbackUrl)
        )
        val client = EngineStreamClient(api)
        client.connect("http://127.0.0.1:6878")

        val magnet = "magnet:?xt=urn:btih:AAA"
        val result = client.resolveStream(magnet)

        assertTrue(result is AppResult.Success)
        assertEquals(playbackUrl, (result as AppResult.Success).data)
        assertEquals("http://127.0.0.1:6878/ace/getstream", api.lastResolveUrl)
        assertEquals("json", api.lastResolveOptions["format"])
        assertEquals("0", api.lastResolveOptions["_idx"])
        assertEquals("0", api.lastResolveOptions["stream_id"])
        assertEquals(magnet, api.lastResolveOptions["magnet"])
        assertEquals("1", api.lastResolveOptions["auto_start_stream"])
        assertEquals("10", api.lastResolveOptions["manifest_p2p_wait_timeout"])
        assertTrue(api.lastResolveOptions["sid"].orEmpty().startsWith("engineProxy-"))
        assertTrue(api.lastResolveOptions["client_session_id"]?.toIntOrNull() != null)
    }

    @Test
    fun resolve_missingPlaybackUrlReturnsErrorInsteadOfControlUrl() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = mapOf(
                "error" to null,
                "response" to mapOf("stat_url" to "http://127.0.0.1:6878/ace/stat/123")
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveStream("magnet:?xt=urn:btih:BBB")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("playback_url"))
    }

    @Test
    fun resolve_infohashNormalizesToMagnetParameter() = runTest {
        val playbackUrl = "http://127.0.0.1:6878/ace/stream/infohash"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = playbackPayload(playbackUrl)
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val infoHash = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
        val result = client.resolveStream("infohash:$infoHash")

        assertTrue(result is AppResult.Success)
        assertEquals(playbackUrl, (result as AppResult.Success).data)
        assertEquals(
            "magnet:?xt=urn:btih:$infoHash",
            api.lastResolveOptions["magnet"]
        )
    }

    @Test
    fun resolve_aceSchemeUsesHexContentIdParameter() = runTest {
        val playbackUrl = "http://127.0.0.1:6878/ace/stream/content-id"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = playbackPayload(playbackUrl)
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val contentId = "11223344556677889900AABBCCDDEEFF00112233"
        val result = client.resolveStream("ace://$contentId")

        assertTrue(result is AppResult.Success)
        assertEquals(playbackUrl, (result as AppResult.Success).data)
        assertEquals(contentId.lowercase(), api.lastResolveOptions["content_id"])
        assertTrue("id" !in api.lastResolveOptions)
    }

    @Test
    fun resolve_engineErrorIsReported() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf("error" to "missing content descriptor")
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveStream("https://example.org/live.acelive")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("missing content descriptor"))
    }

    @Test
    fun resolveContentIdInfoHash_usesMetadataApiAndReturnsBitTorrentHash() = runTest {
        val contentId = "11223344556677889900AABBCCDDEEFF00112233"
        val infoHash = "0A4848271C91CE2D8965CE416267C25047DC8141"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf(
                "result" to mapOf(
                    "infohash" to infoHash,
                    "transport_type" to "bt",
                    "type" to "vod"
                )
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveContentIdInfoHash("acestream://$contentId")

        assertTrue(result is AppResult.Success)
        assertEquals(infoHash.lowercase(), (result as AppResult.Success).data)
        assertEquals("http://127.0.0.1:6878/server/api", api.lastResolveUrl)
        assertEquals("2", api.lastResolveOptions["api_version"])
        assertEquals("get_media_files", api.lastResolveOptions["method"])
        assertEquals(contentId.lowercase(), api.lastResolveOptions["content_id"])
        assertEquals("full", api.lastResolveOptions["mode"])
        assertEquals("1", api.lastResolveOptions["expand_wrapper"])
        assertEquals("1", api.lastResolveOptions["dump_transport_file"])
    }

    @Test
    fun resolveContentIdInfoHash_rejectsAceLiveTransport() = runTest {
        val contentId = "11223344556677889900AABBCCDDEEFF00112233"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf(
                "result" to mapOf(
                    "infohash" to "0a4848271c91ce2d8965ce416267c25047dc8141",
                    "transport_type" to "bt",
                    "type" to "live"
                )
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveContentIdInfoHash("acestream://$contentId")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("Ace live protocol"))
    }

    @Test
    fun resolveContentIdInfoHash_rejectsNonBitTorrentTransport() = runTest {
        val contentId = "11223344556677889900AABBCCDDEEFF00112233"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf(
                "result" to mapOf(
                    "infohash" to "0a4848271c91ce2d8965ce416267c25047dc8141",
                    "transport_type" to "hls"
                )
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveContentIdInfoHash("acestream://$contentId")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("unsupported transport type"))
    }

    @Test
    fun resolveContentIdInfoHash_rejectsMissingInfoHash() = runTest {
        val contentId = "11223344556677889900AABBCCDDEEFF00112233"
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf(
                "result" to mapOf(
                    "name" to "Video",
                    "transport_type" to "bt",
                    "type" to "vod"
                )
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveContentIdInfoHash("acestream://$contentId")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("valid BitTorrent infohash"))
    }

    private class FakeApi(
        private val statusPayload: Map<String, Any?> = emptyMap(),
        private val resolvePayload: Map<String, Any?> = emptyMap()
    ) : EngineStreamApi {
        var lastResolveUrl: String? = null
        var lastResolveOptions: Map<String, String> = emptyMap()

        override suspend fun status(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> = statusPayload

        override suspend fun resolve(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> {
            lastResolveUrl = url
            lastResolveOptions = options
            return resolvePayload
        }
    }

    private companion object {
        fun playbackPayload(playbackUrl: String): Map<String, Any?> = mapOf(
            "error" to null,
            "response" to mapOf(
                "playback_url" to playbackUrl,
                "stat_url" to "http://127.0.0.1:6878/ace/stat/123"
            )
        )
    }
}
