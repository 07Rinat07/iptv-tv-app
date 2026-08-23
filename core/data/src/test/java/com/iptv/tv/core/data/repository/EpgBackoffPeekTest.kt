package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgBackoffPeekTest {
    @Test
    fun expiredDiagnosticPeekDoesNotRemoveEntryOrChangeNextEviction() {
        var now = 1_000L
        val cache = EpgFailureBackoffCache(maxEntries = 2) { now }
        cache.record(
            "active-eldest",
            "active",
            retryAfterMs = 10_000L,
            kind = EpgFailureKind.TRANSIENT
        )
        cache.record(
            "expires-newest",
            "expired",
            retryAfterMs = 100L,
            kind = EpgFailureKind.TRANSIENT
        )
        now = 1_200L

        assertNull(cache.peekActive("expires-newest"))
        assertEquals(2, cache.size())

        cache.record("new", "new", retryAfterMs = 10_000L, kind = EpgFailureKind.TRANSIENT)

        assertNull(cache.peekActive("active-eldest"))
        assertNull(cache.peekActive("expires-newest"))
        assertEquals("new", cache.peekActive("new")?.reason)
    }
}
