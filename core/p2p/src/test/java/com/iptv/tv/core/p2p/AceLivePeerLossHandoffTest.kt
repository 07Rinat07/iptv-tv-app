package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerLossHandoffTest {
    @Test
    fun disconnectedPrimaryImmediatelyHandsOutstandingPiecesToExistingBackup() {
        val coordinator = AceLiveRecoveryCoordinator(
            maxInFlightPerPeer = 2,
            policy = AceLiveRecoveryPolicy(
                requestTimeoutMillis = 4_000L,
                staleUpstreamTimeoutMillis = 12_000L,
                requestCheckIntervalMillis = 1_000L,
                maxPieceAdvance = 256L
            )
        )
        coordinator.updatePeer(peer(id = 1L, max = 140L))

        val initial = coordinator.assign(
            nextNeeded = 100L,
            head = 140L,
            nowMillis = 0L
        )
        assertEquals(listOf(100L, 101L), initial.map { it.piece })
        assertTrue(initial.all { it.peerId == 1L })

        // The backup is already healthy before the primary disappears. A disconnect therefore
        // must not wait for the normal request-timeout sweep before useful work can continue.
        coordinator.updatePeer(peer(id = 2L, max = 130L))
        val requeued = coordinator.removePeer(1L)

        assertEquals(listOf(100L, 101L), requeued)
        assertNull(coordinator.ownerOf(100L))
        assertNull(coordinator.ownerOf(101L))
        assertEquals(0, coordinator.inFlightCount())

        val reassigned = coordinator.assign(
            nextNeeded = 100L,
            head = 140L,
            nowMillis = 1L
        )

        assertEquals(listOf(100L, 101L), reassigned.map { it.piece })
        assertTrue(reassigned.all { it.peerId == 2L })
        assertEquals(2L, coordinator.ownerOf(100L))
        assertEquals(2L, coordinator.ownerOf(101L))

        val recovery = coordinator.evaluate(nextNeeded = 100L, nowMillis = 1L)
        assertTrue(recovery.timedOutRequests.isEmpty())
        assertNull(recovery.cursorAdvance)
    }

    private fun peer(id: Long, max: Long) = AceLivePeerWindow(
        peerId = id,
        minPiece = 100L,
        maxPiece = max,
        unchoked = true
    )
}
