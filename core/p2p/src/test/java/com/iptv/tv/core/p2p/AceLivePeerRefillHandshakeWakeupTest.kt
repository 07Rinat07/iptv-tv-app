package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerRefillHandshakeWakeupTest {
    @Test
    fun `accepted handshake requests immediate refill`() {
        assertTrue(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.HandshakeAccepted(peerId = 1L)
            )
        )
    }

    @Test
    fun `rejected handshake requests immediate alternative refill`() {
        assertTrue(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.HandshakeRejected(
                    peerId = 1L,
                    reason = AceLivePeerHandshakeRejectReason.SWARM_KEY_MISMATCH
                )
            )
        )
    }

    @Test
    fun `transport connection alone does not request refill`() {
        assertFalse(
            aceLivePeerRefillEventShouldWake(
                AceLiveTcpPoolEvent.TransportConnected(
                    peerId = 1L,
                    reconnectAttempt = 0
                )
            )
        )
    }
}
