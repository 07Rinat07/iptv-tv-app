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
    fun resolve_torrentUsesApiResponseUrl() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 1, "speed" to 10)),
            resolvePayload = mapOf(
                "response" to mapOf("url" to "http://127.0.0.1:6878/stream/resolved")
            )
        )
        val client = EngineStreamClient(api)
        client.connect("http://127.0.0.1:6878")

        val result = client.resolveStream("magnet:?xt=urn:btih:AAA")
        assertTrue(result is AppResult.Success)
        assertEquals(
            "http://127.0.0.1:6878/stream/resolved",
            (result as AppResult.Success).data
        )
    }

    @Test
    fun resolve_torrentBuildsFallbackWhenApiHasNoPlayableUrl() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = mapOf("response" to mapOf("id" to "123"))
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveStream("magnet:?xt=urn:btih:BBB")
        assertTrue(result is AppResult.Success)
        val stream = (result as AppResult.Success).data
        assertTrue(stream.startsWith("http://127.0.0.1:6878/ace/getstream?url="))
    }

    @Test
    fun resolve_infohashNormalizesToMagnetForFallback() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = mapOf("response" to mapOf("id" to "123"))
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveStream("infohash:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        assertTrue(result is AppResult.Success)
        val stream = (result as AppResult.Success).data
        assertTrue(stream.contains("magnet%3A%3Fxt%3Durn%3Abtih%3A"))
        assertTrue(stream.contains("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"))
    }

    @Test
    fun resolve_aceSchemeNormalizesToAcestream() = runTest {
        val api = FakeApi(
            statusPayload = mapOf("response" to mapOf("peers" to 2, "speed" to 20)),
            resolvePayload = mapOf("response" to mapOf("id" to "123"))
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveStream("ace://11223344556677889900AABBCCDDEEFF00112233")
        assertTrue(result is AppResult.Success)
        val stream = (result as AppResult.Success).data
        assertTrue(stream.contains("acestream%3A%2F%2F11223344556677889900AABBCCDDEEFF00112233"))
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
                    "type" to "live"
                )
            )
        )
        val client = EngineStreamClient(api)
        client.connect("127.0.0.1:6878")

        val result = client.resolveContentIdInfoHash("acestream://$contentId")

        assertTrue(result is AppResult.Success)
        assertEquals(infoHash.lowercase(), (result as AppResult.Success).data)
        assertEquals("http://127.0.0.1:6878/server/api", api.lastResolveUrl)
        assertEquals("3", api.lastResolveOptions["api_version"])
        assertEquals("get_media_files", api.lastResolveOptions["method"])
        assertEquals(contentId.lowercase(), api.lastResolveOptions["content_id"])
        assertEquals("brief", api.lastResolveOptions["mode"])
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
                    "name" to "Live channel",
                    "transport_type" to "bt"
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
}
