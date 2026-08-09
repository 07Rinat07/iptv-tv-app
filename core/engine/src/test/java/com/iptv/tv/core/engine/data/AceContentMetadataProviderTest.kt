package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentMetadataProviderTest {
    @Test
    fun chain_fallsThroughErrorsAndReturnsFirstSuccess() = runTest {
        val expected = metadata("0a4848271c91ce2d8965ce416267c25047dc8141")
        val primary = RecordingProvider(AppResult.Error("autonomous provider unavailable"))
        val compatibility = RecordingProvider(AppResult.Success(expected))
        val chain = ChainedAceContentMetadataProvider(listOf(primary, compatibility))

        val result = chain.resolve("acestream://11223344556677889900aabbccddeeff00112233")

        assertTrue(result is AppResult.Success)
        assertEquals(expected, (result as AppResult.Success).data)
        assertEquals(1, primary.calls)
        assertEquals(1, compatibility.calls)
    }

    @Test
    fun chain_stopsAfterFirstSuccessfulProvider() = runTest {
        val expected = metadata("0a4848271c91ce2d8965ce416267c25047dc8141")
        val primary = RecordingProvider(AppResult.Success(expected))
        val compatibility = RecordingProvider(AppResult.Error("must not be called"))
        val chain = ChainedAceContentMetadataProvider(listOf(primary, compatibility))

        val result = chain.resolve("acestream://11223344556677889900aabbccddeeff00112233")

        assertTrue(result is AppResult.Success)
        assertEquals(expected, (result as AppResult.Success).data)
        assertEquals(1, primary.calls)
        assertEquals(0, compatibility.calls)
    }

    @Test
    fun chain_withoutProvidersReturnsExplicitError() = runTest {
        val result = ChainedAceContentMetadataProvider(emptyList())
            .resolve("acestream://11223344556677889900aabbccddeeff00112233")

        assertTrue(result is AppResult.Error)
        assertTrue((result as AppResult.Error).message.contains("No Ace content metadata providers"))
    }

    private class RecordingProvider(
        private val result: AppResult<AceTransportMetadata>
    ) : AceContentMetadataProvider {
        var calls: Int = 0

        override suspend fun resolve(rawSource: String): AppResult<AceTransportMetadata> {
            calls += 1
            return result
        }
    }

    private fun metadata(infoHash: String) = AceTransportMetadata(
        infoHash = infoHash,
        mediaType = "vod",
        transportType = "bt",
        name = "test",
        files = emptyList(),
        transportFileData = null,
        transportFileCacheKey = null,
        wrapperData = null
    )
}
