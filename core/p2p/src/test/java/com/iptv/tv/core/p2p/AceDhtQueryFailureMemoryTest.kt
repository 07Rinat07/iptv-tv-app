package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceDhtQueryFailureMemoryTest {
    @Test
    fun failureBlocksEndpointUntilSuccessClearsIt() {
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 20_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("203.0.113.7", 6881)

        memory.recordFailure(endpoint, nowMillis = 1_000L)

        assertFalse(memory.isEligible(endpoint, nowMillis = 1_001L))
        assertEquals(1, memory.activeFailureCount(nowMillis = 1_001L))

        memory.recordSuccess(endpoint)

        assertTrue(memory.isEligible(endpoint, nowMillis = 1_002L))
        assertEquals(0, memory.activeFailureCount(nowMillis = 1_002L))
    }

    @Test
    fun expiredFailureBecomesEligibleAgain() {
        var now = 5_000L
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { now },
            backoffMillis = 20_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("198.51.100.9", 6881)

        memory.recordFailure(endpoint)
        assertFalse(memory.isEligible(endpoint))

        now += 20_000L

        assertTrue(memory.isEligible(endpoint))
        assertEquals(0, memory.activeFailureCount())
    }

    @Test
    fun boundedMemoryEvictsOldestEndpoint() {
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 20_000L,
            maxEntries = 2
        )
        val first = AceLiveTcpPeerEndpoint("203.0.113.1", 6881)
        val second = AceLiveTcpPeerEndpoint("203.0.113.2", 6881)
        val third = AceLiveTcpPeerEndpoint("203.0.113.3", 6881)

        memory.recordFailure(first)
        memory.recordFailure(second)
        memory.recordFailure(third)

        assertTrue(memory.isEligible(first))
        assertFalse(memory.isEligible(second))
        assertFalse(memory.isEligible(third))
        assertEquals(2, memory.activeFailureCount())
    }

    @Test
    fun endpointHostKeyIsCaseInsensitive() {
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 20_000L
        )

        memory.recordFailure(AceLiveTcpPeerEndpoint("DHT.Example", 6881))

        assertFalse(memory.isEligible(AceLiveTcpPeerEndpoint("dht.example", 6881)))
    }
}
