package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePieceProgressTimeoutRegressionTest {
    @Test
    fun acceptedUniqueChunkRefreshesPieceTimeoutWithoutWideningBound() {
        val coordinator = coordinator()
        coordinator.onPeerEvent(window())

        val initial = coordinator.schedule(nextNeeded = 10, head = 10, nowMillis = 0)
        assertEquals(24, initial.size)

        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1_000.0)
        assertEquals(
            AceLiveChunkDisposition.ACCEPTED,
            coordinator.onChunk(
                chunk(index = 0, header = header),
                nextNeeded = 10,
                nowMillis = 3_500
            ).disposition
        )

        val originalDeadline = coordinator.evaluateRecovery(nextNeeded = 10, nowMillis = 4_000)
        assertTrue(originalDeadline.timedOutRequests.isEmpty())
        assertEquals(7L, coordinator.ownerOf(10))

        val inactivityDeadline = coordinator.evaluateRecovery(nextNeeded = 10, nowMillis = 7_500)
        assertEquals(listOf(10L), inactivityDeadline.timedOutRequests.map { it.piece })
        assertNull(coordinator.ownerOf(10))
    }

    @Test
    fun duplicateChunkDoesNotRefreshPieceTimeout() {
        val coordinator = coordinator()
        coordinator.onPeerEvent(window())
        coordinator.schedule(nextNeeded = 10, head = 10, nowMillis = 0)

        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1_000.0)
        val first = chunk(index = 0, header = header)
        assertEquals(
            AceLiveChunkDisposition.ACCEPTED,
            coordinator.onChunk(first, nextNeeded = 10, nowMillis = 1_000).disposition
        )
        assertEquals(
            AceLiveChunkDisposition.DUPLICATE,
            coordinator.onChunk(first, nextNeeded = 10, nowMillis = 3_900).disposition
        )

        val timeout = coordinator.evaluateRecovery(nextNeeded = 10, nowMillis = 5_000)
        assertEquals(listOf(10L), timeout.timedOutRequests.map { it.piece })
    }

    private fun coordinator() = AceLiveActivePeerCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 32 * 4,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        maxInFlightPerPeer = 1,
        maxOutstandingChunksPerPiece = 24,
        recoveryPolicy = AceLiveRecoveryPolicy(
            requestTimeoutMillis = 4_000,
            staleUpstreamTimeoutMillis = 12_000,
            requestCheckIntervalMillis = 1_000,
            maxPieceAdvance = 256
        )
    )

    private fun window() = AceLivePeerWindow(
        peerId = 7,
        minPiece = 10,
        maxPiece = 20,
        unchoked = true
    ).let(::AceLivePeerWindowUpdated)

    private fun chunk(
        index: Int,
        header: ByteArray
    ) = AceLiveIncomingChunk(
        peerId = 7,
        streamIndex = 0,
        piece = 10,
        chunkIndex = index,
        pieceHeader = header,
        data = ByteArray(4)
    )
}
