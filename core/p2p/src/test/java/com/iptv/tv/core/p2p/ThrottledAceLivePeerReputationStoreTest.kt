package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class ThrottledAceLivePeerReputationStoreTest {
    @Test
    fun producerEvidenceIsPersistedImmediatelyThenThrottledPerSwarmEndpoint() {
        val delegate = RecordingPeerReputationStore()
        val store = ThrottledAceLivePeerReputationStore(
            delegate = delegate,
            producerEvidenceIntervalMillis = 60_000L
        )
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 7 }
        val endpoint = AceLiveTcpPeerEndpoint("203.0.113.10", 8621)

        store.recordMediaProduced(swarm, endpoint, 1_000L)
        store.recordMediaProduced(swarm, endpoint, 30_000L)
        store.recordMediaProduced(swarm, endpoint, 60_999L)
        store.recordMediaProduced(swarm, endpoint, 61_000L)

        assertEquals(listOf(1_000L, 61_000L), delegate.mediaProducedAt)
    }

    @Test
    fun producerThrottleIsIsolatedBySwarmAndEndpoint() {
        val delegate = RecordingPeerReputationStore()
        val store = ThrottledAceLivePeerReputationStore(
            delegate = delegate,
            producerEvidenceIntervalMillis = 60_000L
        )
        val swarmA = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 1 }
        val swarmB = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 2 }
        val first = AceLiveTcpPeerEndpoint("198.51.100.10", 8621)
        val second = AceLiveTcpPeerEndpoint("198.51.100.11", 8621)

        store.recordMediaProduced(swarmA, first, 5_000L)
        store.recordMediaProduced(swarmA, first, 6_000L)
        store.recordMediaProduced(swarmA, second, 6_000L)
        store.recordMediaProduced(swarmB, first, 6_000L)

        assertEquals(listOf(5_000L, 6_000L, 6_000L), delegate.mediaProducedAt)
    }

    @Test
    fun handshakeAndFailureEvidenceAreNeverThrottled() {
        val delegate = RecordingPeerReputationStore()
        val store = ThrottledAceLivePeerReputationStore(delegate)
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 3 }
        val endpoint = AceLiveTcpPeerEndpoint("192.0.2.20", 8621)

        store.recordHandshakeAccepted(swarm, endpoint, 100L)
        store.recordHandshakeAccepted(swarm, endpoint, 101L)
        store.recordFinalFailure(swarm, endpoint, 102L)
        store.recordFinalFailure(swarm, endpoint, 103L)

        assertEquals(listOf(100L, 101L), delegate.handshakeAcceptedAt)
        assertEquals(listOf(102L, 103L), delegate.finalFailureAt)
    }

    private class RecordingPeerReputationStore : AceLivePeerReputationStore {
        val handshakeAcceptedAt = mutableListOf<Long>()
        val mediaProducedAt = mutableListOf<Long>()
        val finalFailureAt = mutableListOf<Long>()

        override fun snapshot(
            swarmKey: ByteArray,
            endpoint: AceLiveTcpPeerEndpoint,
            nowMillis: Long
        ): AceLivePeerReputationSnapshot? = null

        override fun recordHandshakeAccepted(
            swarmKey: ByteArray,
            endpoint: AceLiveTcpPeerEndpoint,
            nowMillis: Long
        ) {
            handshakeAcceptedAt += nowMillis
        }

        override fun recordMediaProduced(
            swarmKey: ByteArray,
            endpoint: AceLiveTcpPeerEndpoint,
            nowMillis: Long
        ) {
            mediaProducedAt += nowMillis
        }

        override fun recordFinalFailure(
            swarmKey: ByteArray,
            endpoint: AceLiveTcpPeerEndpoint,
            nowMillis: Long
        ) {
            finalFailureAt += nowMillis
        }
    }
}
