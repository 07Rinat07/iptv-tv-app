package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveRecoveryCoordinatorTest {
    @Test
    fun timedOutAssignmentIsRequeuedAfterRequestTimeout() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 10, max = 30, unchoked = true))

        assertEquals(listOf(10L, 11L), coordinator.assign(10, 30, nowMillis = 0).map { it.piece })
        assertEquals(1L, coordinator.ownerOf(10))
        assertEquals(1L, coordinator.ownerOf(11))

        val early = coordinator.evaluate(10, nowMillis = 3_000)
        assertTrue(early.timedOutRequests.isEmpty())
        assertEquals(1L, coordinator.ownerOf(10))
        assertEquals(1L, coordinator.ownerOf(11))

        val timeout = coordinator.evaluate(10, nowMillis = 4_000)
        assertEquals(
            listOf(
                AceLiveTimedOutRequest(piece = 10, previousPeerId = 1),
                AceLiveTimedOutRequest(piece = 11, previousPeerId = 1)
            ),
            timeout.timedOutRequests
        )
        assertNull(coordinator.ownerOf(10))
        assertNull(coordinator.ownerOf(11))
        assertEquals(0, coordinator.inFlightCount())

        assertEquals(
            listOf(10L, 11L),
            coordinator.assign(10, 30, nowMillis = 4_000).map { it.piece }
        )
    }

    @Test
    fun futurePieceCompletionDoesNotResetContiguousStallTimer() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 100, max = 200, unchoked = true))
        val assignments = coordinator.assign(100, 200, nowMillis = 0)
        assertEquals(listOf(100L, 101L), assignments.map { it.piece })

        coordinator.complete(101)

        val plan = coordinator.evaluate(100, nowMillis = 12_000)
        assertTrue(plan.poolStale)
    }

    @Test
    fun evictedGapIsSuggestedOnlyAfterTimeoutAndWithoutCoverage() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 105, max = 140, unchoked = true))

        assertTrue(coordinator.assign(100, 140, nowMillis = 0).isEmpty())
        assertNull(coordinator.evaluate(100, nowMillis = 3_000).cursorAdvance)

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), plan.cursorAdvance)
        assertFalse(plan.gapBeyondAdvanceLimit)
    }

    @Test
    fun coveredCursorIsNeverSkippedEvenWhenStalled() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 100, max = 150, unchoked = true))
        coordinator.assign(100, 150, nowMillis = 0)

        val plan = coordinator.evaluate(100, nowMillis = 8_000)

        assertNull(plan.cursorAdvance)
        assertFalse(plan.gapBeyondAdvanceLimit)
    }

    @Test
    fun farEvictedGapRequiresReconnectPolicyInsteadOfLargeSkip() {
        val coordinator = coordinator(maxPieceAdvance = 8)
        coordinator.updatePeer(peer(id = 1, min = 120, max = 200, unchoked = true))
        coordinator.assign(100, 200, nowMillis = 0)

        val plan = coordinator.evaluate(100, nowMillis = 4_000)

        assertNull(plan.cursorAdvance)
        assertTrue(plan.gapBeyondAdvanceLimit)
    }

    @Test
    fun explicitAdvancePrunesOldRequestsAndAllowsSchedulingAtRecoveredCursor() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 100, max = 103, unchoked = true))
        assertEquals(listOf(100L, 101L), coordinator.assign(100, 103, nowMillis = 0).map { it.piece })

        coordinator.updatePeer(peer(id = 1, min = 105, max = 130, unchoked = true))
        val plan = coordinator.evaluate(100, nowMillis = 4_000)
        val advance = requireNotNull(plan.cursorAdvance)

        coordinator.applyCursorAdvance(advance, nowMillis = 4_000)

        assertEquals(0, coordinator.inFlightCount())
        assertEquals(listOf(105L, 106L), coordinator.assign(105, 130, nowMillis = 4_000).map { it.piece })
    }

    @Test
    fun contiguousProgressResetsStalePoolTimer() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 100, max = 200, unchoked = true))
        coordinator.assign(100, 200, nowMillis = 0)

        coordinator.onCursorAdvanced(101, nowMillis = 5_000)

        assertFalse(coordinator.evaluate(101, nowMillis = 12_000).poolStale)
        assertTrue(coordinator.evaluate(101, nowMillis = 17_000).poolStale)
    }

    @Test
    fun stalePoolSignalDoesNotRemoveReachablePeer() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 7, min = 50, max = 80, unchoked = true))
        coordinator.assign(50, 80, nowMillis = 0)

        val plan = coordinator.evaluate(50, nowMillis = 12_000)

        assertTrue(plan.poolStale)
        assertEquals(80L, coordinator.highestAdvertisedHead())
        assertEquals(7L, coordinator.assign(50, 80, nowMillis = 12_000).first().peerId)
    }

    @Test
    fun removedPeerForgetsOutstandingTimeoutState() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 20, max = 30, unchoked = true))
        coordinator.assign(20, 30, nowMillis = 0)

        assertEquals(listOf(20L, 21L), coordinator.removePeer(1))
        val plan = coordinator.evaluate(20, nowMillis = 8_000)

        assertTrue(plan.timedOutRequests.isEmpty())
        assertFalse(plan.poolStale)
    }

    @Test
    fun recoverySweepIsThrottledByCheckInterval() {
        val coordinator = coordinator(requestCheckIntervalMillis = 1_000)
        coordinator.updatePeer(peer(id = 1, min = 5, max = 20, unchoked = true))
        coordinator.assign(5, 20, nowMillis = 0)

        assertTrue(coordinator.evaluate(5, nowMillis = 1_000).timedOutRequests.isEmpty())
        assertTrue(coordinator.evaluate(5, nowMillis = 1_500).timedOutRequests.isEmpty())
        assertTrue(coordinator.evaluate(5, nowMillis = 2_000).timedOutRequests.isEmpty())
        assertTrue(coordinator.evaluate(5, nowMillis = 3_000).timedOutRequests.isEmpty())

        val timedOut = coordinator.evaluate(5, nowMillis = 4_000)
        assertEquals(listOf(5L, 6L), timedOut.timedOutRequests.map { it.piece })
    }

    @Test(expected = IllegalArgumentException::class)
    fun policyRejectsStaleTimeoutNotGreaterThanRequestTimeout() {
        AceLiveRecoveryPolicy(
            requestTimeoutMillis = 4_000,
            staleUpstreamTimeoutMillis = 4_000,
            requestCheckIntervalMillis = 1_000,
            maxPieceAdvance = 256
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun staleCursorAdvanceCannotBeApplied() {
        val coordinator = coordinator()
        coordinator.updatePeer(peer(id = 1, min = 105, max = 130, unchoked = true))
        coordinator.assign(100, 130, nowMillis = 0)
        val advance = requireNotNull(coordinator.evaluate(100, nowMillis = 4_000).cursorAdvance)

        coordinator.onCursorAdvanced(101, nowMillis = 4_000)
        coordinator.applyCursorAdvance(advance, nowMillis = 4_000)
    }

    private fun coordinator(
        requestCheckIntervalMillis: Long = 1_000,
        maxPieceAdvance: Long = 256
    ) = AceLiveRecoveryCoordinator(
        maxInFlightPerPeer = 2,
        policy = AceLiveRecoveryPolicy(
            requestTimeoutMillis = 4_000,
            staleUpstreamTimeoutMillis = 12_000,
            requestCheckIntervalMillis = requestCheckIntervalMillis,
            maxPieceAdvance = maxPieceAdvance
        )
    )

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
