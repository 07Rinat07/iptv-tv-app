package com.iptv.tv.core.data.repository

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgSuspendingCandidateLoaderTest {
    @Test
    fun healthyFallbackStillPrecedesCapturedStalePrimary() = runTest {
        val attempts = mutableListOf<String>()
        val cache = mutableMapOf("primary" to "stale-primary")

        val results = loadEpgCandidatesFreshFirstSuspending(
            candidates = listOf("primary", "fallback"),
            loadFresh = { url ->
                attempts += "fresh:$url"
                when (url) {
                    "primary" -> throw IOException("timeout")
                    "fallback" -> {
                        cache.clear()
                        "fresh-fallback"
                    }
                    else -> error("unexpected candidate")
                }
            },
            captureStaleFallback = { url ->
                attempts += "stale:$url"
                cache[url]
            },
            onLoadError = {}
        )

        assertEquals(
            listOf(
                EpgCandidateLoad("fallback", "fresh-fallback", servedFromStaleFallback = false),
                EpgCandidateLoad("primary", "stale-primary", servedFromStaleFallback = true)
            ),
            results
        )
        assertEquals(
            listOf("fresh:primary", "stale:primary", "fresh:fallback"),
            attempts
        )
    }

    @Test
    fun lowMemoryDropsPreviouslyCapturedStaleFallback() = runTest {
        val cache = mutableMapOf("primary" to "stale-primary")

        val results = loadEpgCandidatesFreshFirstSuspending(
            candidates = listOf("primary", "fallback"),
            loadFresh = { url ->
                when (url) {
                    "primary" -> throw IOException("timeout")
                    "fallback" -> throw EpgLowMemoryException("low heap")
                    else -> error("unexpected candidate")
                }
            },
            captureStaleFallback = { url -> cache[url] },
            onLoadError = {}
        )

        assertTrue(results.isEmpty())
    }

    @Test
    fun cancellationIsPropagatedWithoutFallbackOrErrorCallback() = runTest {
        var capturedStale = false
        var reportedError = false

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.test.runTest {
                loadEpgCandidatesFreshFirstSuspending(
                    candidates = listOf("primary"),
                    loadFresh = { throw CancellationException("cancelled") },
                    captureStaleFallback = {
                        capturedStale = true
                        "stale"
                    },
                    onLoadError = { reportedError = true }
                )
            }
        }

        assertTrue(!capturedStale)
        assertTrue(!reportedError)
    }
}
