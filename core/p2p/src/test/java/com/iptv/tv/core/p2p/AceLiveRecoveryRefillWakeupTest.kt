package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveRecoveryRefillWakeupTest {
    @Test
    fun `timed out piece requests immediate refill`() {
        assertTrue(
            aceLiveRecoveryShouldWakePeerRefill(
                AceLiveRecoveryPlan(
                    timedOutRequests = listOf(
                        AceLiveTimedOutRequest(
                            piece = 42L,
                            previousPeerId = 7L,
                            acceptedChunks = 32,
                            assignmentAgeMillis = 8_000L,
                            progressAgeMillis = 6_000L
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `stale pool alone does not create level triggered refill wakeup`() {
        assertFalse(
            aceLiveRecoveryShouldWakePeerRefill(
                AceLiveRecoveryPlan(poolStale = true)
            )
        )
    }

    @Test
    fun `cursor advance alone does not request refill`() {
        assertFalse(
            aceLiveRecoveryShouldWakePeerRefill(
                AceLiveRecoveryPlan(
                    cursorAdvance = AceLiveCursorAdvance(fromPiece = 42L, toPiece = 43L)
                )
            )
        )
    }

    @Test
    fun `timeout recovery probe is consumed exactly once`() {
        val probe = AceLiveRecoveryPeerProbe()

        probe.request()

        assertEquals(1, probe.consumeCombinedWith(existingProbePeers = 0))
        assertEquals(0, probe.consumeCombinedWith(existingProbePeers = 0))
    }

    @Test
    fun `multiple timeout edges coalesce into one recovery probe`() {
        val probe = AceLiveRecoveryPeerProbe()

        repeat(8) { probe.request() }

        assertEquals(1, probe.consumeCombinedWith(existingProbePeers = 0))
        assertEquals(0, probe.consumeCombinedWith(existingProbePeers = 0))
    }

    @Test
    fun `recovery probe does not stack on stronger adaptive pressure`() {
        val probe = AceLiveRecoveryPeerProbe()

        probe.request()

        assertEquals(2, probe.consumeCombinedWith(existingProbePeers = 2))
        assertEquals(2, probe.consumeCombinedWith(existingProbePeers = 2))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `recovery probe rejects negative existing demand`() {
        AceLiveRecoveryPeerProbe().consumeCombinedWith(existingProbePeers = -1)
    }
}
