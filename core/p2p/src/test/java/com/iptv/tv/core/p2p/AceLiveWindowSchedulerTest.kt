package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveWindowSchedulerTest {
    @Test
    fun assignsFromPlaybackCursorAndRespectsPerPeerCapacity() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 100, max = 110, unchoked = true))
        scheduler.updatePeer(peer(id = 2, min = 100, max = 120, unchoked = true))

        val assigned = scheduler.assign(nextNeeded = 100, head = 120)

        assertEquals(listOf(100L, 101L, 102L, 103L), assigned.map { it.piece })
        assertEquals(4, scheduler.inFlightCount())
        assertEquals(2, scheduler.peerInFlightCount(1))
        assertEquals(2, scheduler.peerInFlightCount(2))
        assertEquals(2L, assigned.first().peerId)
    }

    @Test
    fun chokedPeerDoesNotReceiveNewAssignments() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 3)
        scheduler.updatePeer(peer(id = 1, min = 0, max = 20, unchoked = false))

        assertTrue(scheduler.assign(nextNeeded = 0, head = 20).isEmpty())
        assertEquals(0, scheduler.inFlightCount())
    }

    @Test
    fun outstandingPieceIsNeverAssignedTwice() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 1)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 20, unchoked = true))
        scheduler.updatePeer(peer(id = 2, min = 10, max = 20, unchoked = true))

        val first = scheduler.assign(nextNeeded = 10, head = 20)
        val second = scheduler.assign(nextNeeded = 10, head = 20)

        assertEquals(listOf(10L, 11L), first.map { it.piece })
        assertTrue(second.isEmpty())
        assertEquals(2, scheduler.inFlightCount())
    }

    @Test
    fun droppedPeerRequeuesItsUnfinishedPieces() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 50, max = 60, unchoked = true))

        assertEquals(listOf(50L, 51L), scheduler.assign(50, 60).map { it.piece })
        assertEquals(listOf(50L, 51L), scheduler.removePeer(1))
        assertEquals(0, scheduler.inFlightCount())

        scheduler.updatePeer(peer(id = 2, min = 50, max = 70, unchoked = true))
        val reassigned = scheduler.assign(50, 70)

        assertEquals(listOf(50L, 51L), reassigned.map { it.piece })
        assertTrue(reassigned.all { it.peerId == 2L })
    }

    @Test
    fun movingPeerWindowPastOutstandingPieceRequeuesIt() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 100, max = 110, unchoked = true))
        scheduler.assign(100, 110)

        val requeued = scheduler.updatePeer(
            peer(id = 1, min = 101, max = 111, unchoked = true)
        )

        assertEquals(listOf(100L), requeued)
        assertNull(scheduler.ownerOf(100))
        assertEquals(1, scheduler.inFlightCount())
    }

    @Test
    fun schedulerNeverSilentlySkipsUncoveredNextNeededPiece() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 4)
        scheduler.updatePeer(peer(id = 1, min = 105, max = 120, unchoked = true))

        val assigned = scheduler.assign(nextNeeded = 100, head = 120)

        assertTrue(assigned.isEmpty())
        assertFalse(scheduler.anyUnchokedPeerCovers(100))
        assertEquals(105L, scheduler.lowestAvailablePiece())
    }

    @Test
    fun explicitCursorAdvanceAllowsRecoveryAfterEvictedGap() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 105, max = 120, unchoked = true))

        assertTrue(scheduler.assign(nextNeeded = 100, head = 120).isEmpty())

        val recoveryCursor = scheduler.lowestAvailablePiece()
        assertEquals(105L, recoveryCursor)
        val assigned = scheduler.assign(nextNeeded = recoveryCursor!!, head = 120)

        assertEquals(listOf(105L, 106L), assigned.map { it.piece })
    }

    @Test
    fun completionFreesCapacityAndCursorContinuesInOrder() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 0, max = 100, unchoked = true))

        assertEquals(listOf(0L, 1L), scheduler.assign(0, 100).map { it.piece })
        scheduler.complete(0)

        val next = scheduler.assign(nextNeeded = 1, head = 100)

        assertEquals(listOf(2L), next.map { it.piece })
        assertEquals(1L, scheduler.ownerOf(1))
        assertEquals(1L, scheduler.ownerOf(2))
    }

    @Test
    fun retryMakesTimedOutPieceImmediatelyReassignable() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 1)
        scheduler.updatePeer(peer(id = 1, min = 7, max = 20, unchoked = true))
        assertEquals(listOf(7L), scheduler.assign(7, 20).map { it.piece })

        scheduler.retry(7)
        scheduler.setUnchoked(1, false)
        scheduler.updatePeer(peer(id = 2, min = 7, max = 30, unchoked = true))

        val reassigned = scheduler.assign(7, 30)
        assertEquals(listOf(AceLivePieceAssignment(peerId = 2, piece = 7)), reassigned)
    }

    @Test
    fun hostileHugeHeadCannotCreateUnboundedAssignmentBatch() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 3)
        scheduler.updatePeer(
            peer(id = 1, min = 0, max = Long.MAX_VALUE, unchoked = true)
        )

        val assigned = scheduler.assign(nextNeeded = 0, head = Long.MAX_VALUE)

        assertEquals(listOf(0L, 1L, 2L), assigned.map { it.piece })
        assertEquals(3, scheduler.inFlightCount())
    }

    @Test
    fun advertisedHeadQueriesOnlyUnchokedPeers() {
        val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer = 2)
        scheduler.updatePeer(peer(id = 1, min = 10, max = 50, unchoked = false))
        scheduler.updatePeer(peer(id = 2, min = 20, max = 40, unchoked = true))

        assertEquals(20L, scheduler.lowestAvailablePiece())
        assertEquals(40L, scheduler.highestAdvertisedHead())
        assertTrue(scheduler.anyUnchokedPeerCovers(25))
        assertFalse(scheduler.anyUnchokedPeerCovers(45))
    }

    private fun peer(
        id: Long,
        min: Long,
        max: Long,
        unchoked: Boolean
    ) = AceLivePeerWindow(
        peerId = id,
        minPiece = min,
        maxPiece = max,
        unchoked = unchoked
    )
}
