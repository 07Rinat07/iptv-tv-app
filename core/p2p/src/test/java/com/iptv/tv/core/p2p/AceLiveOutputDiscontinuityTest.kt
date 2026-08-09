package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveOutputDiscontinuityTest {
    @Test
    fun appliedRecoveryJumpSurfacesExplicitOutputDiscontinuity() {
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 100,
            maxInFlightPerPeer = 1
        )
        session.onPeerWindow(
            AceLivePeerWindow(
                peerId = 7,
                minPiece = 105,
                maxPiece = 130,
                unchoked = true
            )
        )

        assertTrue(session.schedule(head = 130, nowMillis = 0).isEmpty())
        val advance = requireNotNull(session.evaluateRecovery(nowMillis = 4_000).cursorAdvance)

        val applied = session.applyRecoveryAdvance(advance, nowMillis = 4_000)
        val discontinuity = assertNotNull(applied.outputDiscontinuity)

        assertEquals(100L, discontinuity.fromPiece)
        assertEquals(105L, discontinuity.toPiece)
        assertEquals(5L, discontinuity.skippedPieces)
        assertEquals(
            AceLiveOutputDiscontinuityReason.RECOVERY_EVICTED_GAP,
            discontinuity.reason
        )
        assertEquals(105L, applied.nextNeededPiece)
        assertTrue(applied.emittedPieces.isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun discontinuityRejectsNonForwardRange() {
        AceLiveOutputDiscontinuity(
            fromPiece = 10,
            toPiece = 10,
            reason = AceLiveOutputDiscontinuityReason.RECOVERY_EVICTED_GAP
        )
    }
}
