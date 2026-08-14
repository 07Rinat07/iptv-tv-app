package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class P2pRapidZapGateTest {
    @Test
    fun `first request is immediate`() {
        val gate = P2pRapidZapGate()

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
    }

    @Test
    fun `request inside rapid window gets settle delay`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
        assertEquals(550L, gate.onRequest(nowMs = 10_300L))
        assertEquals(550L, gate.onRequest(nowMs = 10_850L))
    }

    @Test
    fun `request at rapid window boundary is coalesced`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
        assertEquals(550L, gate.onRequest(nowMs = 11_200L))
    }

    @Test
    fun `request after rapid window is immediate and reanchors window`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
        assertEquals(0L, gate.onRequest(nowMs = 11_201L))
        assertEquals(550L, gate.onRequest(nowMs = 11_500L))
    }

    @Test
    fun `reset makes next request immediate`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
        assertEquals(550L, gate.onRequest(nowMs = 10_200L))
        gate.reset()

        assertEquals(0L, gate.onRequest(nowMs = 10_300L))
    }

    @Test
    fun `backward monotonic value reanchors without delay`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 10_000L))
        assertEquals(0L, gate.onRequest(nowMs = 9_000L))
        assertEquals(550L, gate.onRequest(nowMs = 9_100L))
    }

    @Test
    fun `timestamp zero is a valid first request`() {
        val gate = P2pRapidZapGate(rapidWindowMs = 1_200L, settleDelayMs = 550L)

        assertEquals(0L, gate.onRequest(nowMs = 0L))
        assertEquals(550L, gate.onRequest(nowMs = 1L))
    }

    @Test
    fun `invalid configuration is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            P2pRapidZapGate(rapidWindowMs = -1L)
        }
        assertThrows(IllegalArgumentException::class.java) {
            P2pRapidZapGate(settleDelayMs = -1L)
        }
    }
}
