package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgStreamingSafetyTest {
    @Test
    fun boundedInputAllowsPayloadAtExactLimit() {
        val payload = byteArrayOf(1, 2, 3, 4)
        val stream = EpgBoundedInputStream(ByteArrayInputStream(payload), maxBytes = 4)

        assertEquals(payload.toList(), stream.readBytes().toList())
    }

    @Test
    fun boundedInputRejectsPayloadPastLimitWithoutReadingWholeBody() {
        val stream = EpgBoundedInputStream(
            ByteArrayInputStream(ByteArray(32) { it.toByte() }),
            maxBytes = 8
        )
        val buffer = ByteArray(8)

        assertEquals(8, stream.read(buffer))
        val error = assertThrows(EpgInputLimitExceededException::class.java) {
            stream.read()
        }
        assertEquals(8L, error.maxBytes)
    }

    @Test
    fun negativeCacheExpiresAndNeverExceedsBound() {
        var now = 1_000L
        val cache = EpgFailureBackoffCache(maxEntries = 2) { now }

        cache.record("a", "bad-a", retryAfterMs = 1_000L)
        cache.record("b", "bad-b", retryAfterMs = 1_000L)
        assertEquals(2, cache.size())
        assertEquals("bad-a", cache.active("a")?.reason)

        cache.record("c", "bad-c", retryAfterMs = 1_000L)
        assertEquals(2, cache.size())
        assertNull(cache.active("b"))
        assertTrue(cache.active("a") != null || cache.active("c") != null)

        now = 2_100L
        assertNull(cache.active("a"))
        assertNull(cache.active("c"))
    }

    @Test
    fun staleFallbackPolicyKeepsFreshTtlAndBoundsGraceWindow() {
        val freshTtlMs = 15L * 60L * 1_000L
        val maxStaleAgeMs = 2L * 60L * 60L * 1_000L
        val loadedAtMs = 10_000L

        assertEquals(
            EpgCacheFreshness.FRESH,
            EpgStaleFallbackPolicy.freshness(
                loadedAtMs = loadedAtMs,
                nowMs = loadedAtMs + freshTtlMs,
                freshTtlMs = freshTtlMs,
                maxStaleAgeMs = maxStaleAgeMs
            )
        )
        assertEquals(
            EpgCacheFreshness.STALE_FALLBACK,
            EpgStaleFallbackPolicy.freshness(
                loadedAtMs = loadedAtMs,
                nowMs = loadedAtMs + freshTtlMs + 1L,
                freshTtlMs = freshTtlMs,
                maxStaleAgeMs = maxStaleAgeMs
            )
        )
        assertEquals(
            EpgCacheFreshness.STALE_FALLBACK,
            EpgStaleFallbackPolicy.freshness(
                loadedAtMs = loadedAtMs,
                nowMs = loadedAtMs + maxStaleAgeMs,
                freshTtlMs = freshTtlMs,
                maxStaleAgeMs = maxStaleAgeMs
            )
        )
        assertEquals(
            EpgCacheFreshness.EXPIRED,
            EpgStaleFallbackPolicy.freshness(
                loadedAtMs = loadedAtMs,
                nowMs = loadedAtMs + maxStaleAgeMs + 1L,
                freshTtlMs = freshTtlMs,
                maxStaleAgeMs = maxStaleAgeMs
            )
        )
    }

    @Test
    fun staleFallbackOnlyAllowsExplicitTransientFailures() {
        assertTrue(EpgStaleFallbackPolicy.allowsStale(EpgFailureKind.TRANSIENT))
        assertTrue(!EpgStaleFallbackPolicy.allowsStale(EpgFailureKind.PERMANENT_HTTP))
        assertTrue(!EpgStaleFallbackPolicy.allowsStale(EpgFailureKind.MALFORMED))
        assertTrue(!EpgStaleFallbackPolicy.allowsStale(EpgFailureKind.LOW_MEMORY))
    }

    @Test
    fun failureClassificationKeepsMalformedPermanentAndLowMemoryFailClosed() {
        assertEquals(EpgFailureKind.TRANSIENT, classifyEpgFailure(IOException("timeout")))
        assertEquals(EpgFailureKind.TRANSIENT, classifyEpgFailure(EpgHttpStatusException(503)))
        assertEquals(EpgFailureKind.PERMANENT_HTTP, classifyEpgFailure(EpgHttpStatusException(404)))
        assertEquals(
            EpgFailureKind.MALFORMED,
            classifyEpgFailure(EpgMalformedXmlException("Invalid XMLTV"))
        )
        assertEquals(
            EpgFailureKind.MALFORMED,
            classifyEpgFailure(EpgInputLimitExceededException(maxBytes = 10L))
        )
        assertEquals(
            EpgFailureKind.LOW_MEMORY,
            classifyEpgFailure(EpgLowMemoryException("low heap"))
        )
    }

    @Test
    fun negativeCacheRetainsFailureKindForBackoffDecisions() {
        val cache = EpgFailureBackoffCache(maxEntries = 1) { 1_000L }
        cache.record(
            url = "https://epg.example/guide.xml",
            reason = "HTTP 503",
            retryAfterMs = 1_000L,
            kind = EpgFailureKind.TRANSIENT
        )

        assertEquals(
            EpgFailureKind.TRANSIENT,
            cache.active("https://epg.example/guide.xml")?.kind
        )
    }
}
