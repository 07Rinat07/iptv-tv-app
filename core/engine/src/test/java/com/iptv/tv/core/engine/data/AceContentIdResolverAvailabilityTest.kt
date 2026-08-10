package com.iptv.tv.core.engine.data

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.engine.api.EngineStreamApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentIdResolverAvailabilityTest {
    @Test
    fun externalProvider_reportsTypedUnavailableFailureWhenNoAceResolverEndpointExists() = runTest {
        val client = EngineStreamClient(NoopEngineStreamApi())
        val provider = ExternalEngineAceContentMetadataProvider(client)

        val result = provider.resolve(
            "acestream://11223344556677889900aabbccddeeff00112233"
        )

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertTrue(error.cause is AceContentIdResolverUnavailableException)
        assertEquals(
            AceContentIdResolverUnavailableException.DEFAULT_MESSAGE,
            error.message
        )
        assertEquals(
            "endpoint_unavailable",
            client.observeRuntimeDiagnostics().value.failureCode
        )
    }

    private class NoopEngineStreamApi : EngineStreamApi {
        override suspend fun status(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> = error("status must not be called without an endpoint")

        override suspend fun resolve(
            url: String,
            options: Map<String, String>
        ): Map<String, Any?> = error("resolve must not be called without an endpoint")
    }
}
