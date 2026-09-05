package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveRecoveryRefillWakeupTest {
    @Test
    fun `timed out piece requests one bounded alternative probe`() {
        assertEquals(
            1,
            aceLiveRecoveryRefillProbePeers(
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
    fun `multiple timed out pieces still request only one alternative probe`() {
        assertEquals(
            1,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(
                    timedOutRequests = listOf(
                        AceLiveTimedOutRequest(piece = 42L, previousPeerId = 7L),
                        AceLiveTimedOutRequest(piece = 43L, previousPeerId = 8L)
                    )
                )
            )
        )
    }

    @Test
    fun `stale pool alone does not create level triggered probe demand`() {
        assertEquals(
            0,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(poolStale = true)
            )
        )
    }

    @Test
    fun `cursor advance alone does not request alternative probe`() {
        assertEquals(
            0,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(
                    cursorAdvance = AceLiveCursorAdvance(fromPiece = 42L, toPiece = 43L)
                )
            )
        )
    }
}
