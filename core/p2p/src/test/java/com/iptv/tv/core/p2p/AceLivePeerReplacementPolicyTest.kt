package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerReplacementPolicyTest {
    private val settings = AceLivePeerReplacementSettings(
        targetActivePeers = 2,
        connectionGraceMillis = 1_000L,
        degradedEvidenceMillis = 2_000L,
        noMediaGraceMillis = 5_000L,
        staleMediaMillis = 3_000L,
        replacementCooldownMillis = 10_000L
    )

    @Test
    fun sustainedCriticalPressureAndDegradationAreRequired() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            healthy(2),
            degraded(3)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 11_999L))
        val decision = policy.selectCandidate(
            AceLiveBufferPressure.CRITICAL,
            active,
            peers,
            12_000L
        )

        assertEquals(3L, decision?.peerId)
        assertEquals(AceLivePeerReplacementReason.WINDOW_NOT_USEFUL, decision?.reason)
        assertEquals(2_000L, decision?.degradedForMillis)
    }

    @Test
    fun leavingCriticalClearsAccumulatedEvidence() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(healthy(1), healthy(2), degraded(3))
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.TARGET, active, peers, 11_500L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 12_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 13_999L))
        assertEquals(
            3L,
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                active,
                peers,
                14_000L
            )?.peerId
        )
    }

    @Test
    fun producingPeerIsNeverSelected() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            healthy(2),
            degraded(3).copy(producing = true)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 20_000L))
    }

    @Test
    fun replacementCannotReduceRequestablePoolBelowBaseline() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            degraded(2),
            degraded(3)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 20_000L))
    }

    @Test
    fun cooldownAllowsAtMostOneReplacementAcrossOverlappingCycles() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val initial = listOf(healthy(1), healthy(2), degraded(3), degraded(4))
        val initialActive = setOf(1L, 2L, 3L, 4L)

        assertNull(
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                initialActive,
                initial,
                10_000L
            )
        )
        val first = policy.selectCandidate(
            AceLiveBufferPressure.CRITICAL,
            initialActive,
            initial,
            12_000L
        )
        assertEquals(3L, first?.peerId)

        val afterFirst = listOf(healthy(1), healthy(2), degraded(4))
        val afterFirstActive = setOf(1L, 2L, 4L)
        assertNull(
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                afterFirstActive,
                afterFirst,
                15_000L
            )
        )
        assertEquals(
            4L,
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                afterFirstActive,
                afterFirst,
                22_000L
            )?.peerId
        )
    }

    private fun healthy(peerId: Long) = AceLivePeerQualitySnapshot(
        peerId = peerId,
        connected = true,
        handshaked = true,
        windowUseful = true,
        unchoked = true,
        producing = true,
        recentBytesPerSecond = 1_000_000L,
        mediaAgeMillis = 100L,
        connectedAgeMillis = 20_000L,
        totalMediaBytes = 10_000_000L
    )

    private fun degraded(peerId: Long) = AceLivePeerQualitySnapshot(
        peerId = peerId,
        connected = true,
        handshaked = true,
        windowUseful = false,
        unchoked = true,
        producing = false,
        recentBytesPerSecond = 0L,
        mediaAgeMillis = null,
        connectedAgeMillis = 20_000L,
        totalMediaBytes = 0L
    )
}
