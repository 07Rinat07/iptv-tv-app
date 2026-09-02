package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveRecoveryTimeoutTelemetryTest {
    @Test
    fun timeoutCarriesAuthoritativeAssignmentAndProgressSnapshot() {
        val recovery = AceLiveRecoveryCoordinator(maxInFlightPerPeer = 1)
        recovery.updatePeer(
            AceLivePeerWindow(
                peerId = 1,
                minPiece = 10,
                maxPiece = 20,
                unchoked = true
            )
        )
        val assignments = recovery.assign(
            nextNeeded = 10,
            head = 10,
            nowMillis = 0
        )
        assertEquals(listOf(10L), assignments.map { it.piece })

        recovery.recordProgress(piece = 10, nowMillis = 1)
        val timeout = recovery.evaluate(nextNeeded = 10, nowMillis = 4_001)
            .timedOutRequests
            .single()

        assertEquals(1, timeout.acceptedChunks)
        assertEquals(4_001L, timeout.assignmentAgeMillis)
        assertEquals(4_000L, timeout.progressAgeMillis)
    }
}
