package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveRecoveryPeerDiversityTest {
    @Test
    fun `timed out pieces prefer a different eligible peer on retry`() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 10, max = 30))
        coordinator.updatePeer(peer(id = 2, min = 10, max = 30))

        val initial = coordinator.assign(
            nextNeeded = 10,
            head = 30,
            nowMillis = 0,
            maxInFlightPerPeer = 1
        )
        assertEquals(listOf(1L, 2L), initial.map(AceLivePieceAssignment::peerId))
        assertEquals(listOf(10L, 11L), initial.map(AceLivePieceAssignment::piece))

        val timeout = coordinator.evaluate(10, nowMillis = 4_000)
        assertEquals(
            listOf(
                AceLiveTimedOutRequest(piece = 10, previousPeerId = 1),
                AceLiveTimedOutRequest(piece = 11, previousPeerId = 2)
            ),
            timeout.timedOutRequests
        )

        val retried = coordinator.assign(
            nextNeeded = 10,
            head = 30,
            nowMillis = 4_000,
            maxInFlightPerPeer = 1
        )

        assertEquals(listOf(2L, 1L), retried.map(AceLivePieceAssignment::peerId))
        assertEquals(listOf(10L, 11L), retried.map(AceLivePieceAssignment::piece))
    }

    @Test
    fun `timed out piece falls back to same peer when no alternative exists`() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 7, min = 20, max = 40))

        assertEquals(
            listOf(7L, 7L),
            coordinator.assign(20, 40, nowMillis = 0).map(AceLivePieceAssignment::peerId)
        )
        coordinator.evaluate(20, nowMillis = 4_000)

        assertEquals(
            listOf(7L, 7L),
            coordinator.assign(20, 40, nowMillis = 4_000).map(AceLivePieceAssignment::peerId)
        )
    }

    @Test
    fun `retry preference is consumed after successful reassignment`() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 1)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 30))
        scheduler.updatePeer(peer(id = 2, min = 10, max = 30))

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
        scheduler.retry(10)
        assertEquals(2L, scheduler.assign(10, 10).single().peerId)

        assertEquals(
            listOf(10L),
            scheduler.updatePeer(peer(id = 2, min = 11, max = 30))
        )
        scheduler.updatePeer(peer(id = 2, min = 10, max = 30))

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
    }

    @Test
    fun `completion clears pending retry preference`() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 1)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 30))
        scheduler.updatePeer(peer(id = 2, min = 10, max = 30))

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
        scheduler.retry(10)
        scheduler.complete(10)

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
    }

    @Test
    fun `removing previous peer clears pending retry preference`() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 1)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 30))
        scheduler.updatePeer(peer(id = 2, min = 10, max = 30))

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
        scheduler.retry(10)
        scheduler.removePeer(1)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 30))

        assertEquals(1L, scheduler.assign(10, 10).single().peerId)
    }

    private fun coordinator() = AceLiveRecoveryCoordinator(
        maxInFlightPerPeer = 2,
        policy = AceLiveRecoveryPolicy(
            requestTimeoutMillis = 4_000,
            staleUpstreamTimeoutMillis = 12_000,
            requestCheckIntervalMillis = 1_000,
            maxPieceAdvance = 256
        )
    )

    private fun peer(id: Long, min: Long, max: Long) = AceLivePeerWindow(
        peerId = id,
        minPiece = min,
        maxPiece = max,
        unchoked = true
    )
}
