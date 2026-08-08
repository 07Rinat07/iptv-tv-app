package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentReadAheadPolicyTest {
    @Test
    fun targetsEightMiBForOneMiBPieces() {
        assertEquals(
            TorrentReadAheadPlan(readAheadPieces = 8, elevatedPriorityPieces = 2),
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 1024 * 1024)
        )
    }

    @Test
    fun scalesDownForLargePieces() {
        assertEquals(
            TorrentReadAheadPlan(readAheadPieces = 2, elevatedPriorityPieces = 2),
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 4 * 1024 * 1024)
        )
    }

    @Test
    fun keepsAtLeastOneReadAheadPiece() {
        assertEquals(
            TorrentReadAheadPlan(readAheadPieces = 1, elevatedPriorityPieces = 1),
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 16 * 1024 * 1024)
        )
    }

    @Test
    fun capsVerySmallPiecesToBoundOutstandingPriorityWork() {
        assertEquals(
            TorrentReadAheadPlan(readAheadPieces = 32, elevatedPriorityPieces = 2),
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 64 * 1024)
        )
    }

    @Test
    fun customTargetRoundsUpToWholePieces() {
        assertEquals(
            TorrentReadAheadPlan(readAheadPieces = 3, elevatedPriorityPieces = 2),
            TorrentReadAheadPolicy.plan(
                pieceLengthBytes = 1_000,
                targetBytes = 2_001,
                maxPieces = 10
            )
        )
    }

    @Test
    fun rejectsInvalidConfiguration() {
        assertThrows(IllegalArgumentException::class.java) {
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 1024, targetBytes = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            TorrentReadAheadPolicy.plan(pieceLengthBytes = 1024, maxPieces = 0)
        }
    }
}
