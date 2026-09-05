package com.iptv.tv.core.p2p

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
}
