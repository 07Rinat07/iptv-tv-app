package com.iptv.tv.core.data.repository

import java.io.ByteArrayInputStream
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
}
