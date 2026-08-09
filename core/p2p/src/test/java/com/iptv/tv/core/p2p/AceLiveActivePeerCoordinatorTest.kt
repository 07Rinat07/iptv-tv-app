package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveActivePeerCoordinatorTest {
    @Test
    fun chunkRequestEncodesVerifiedAceWirePayload() {
        val request = AceLiveChunkRequest(
            peerId = 7,
            piece = 0x005067f8,
            chunkIndex = 3,
            beginBytes = 49_152,
            expectedPayloadBytes = 16_384
        )

        assertEquals(6, request.messageId)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 0, 0, 0x50, 0x67, 0xf8.toByte(), 0, 3),
            request.wirePayload()
        )
    }

    @Test
    fun scheduleExpandsPieceAssignmentToEveryChunkIncludingShortTail() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 7, min = 10, max = 20, unchoked = true))

        val requests = coordinator.schedule(nextNeeded = 10, head = 10, nowMillis = 0)

        assertEquals(listOf(0, 1, 2), requests.map { it.chunkIndex })
        assertEquals(listOf(0, 4, 8), requests.map { it.beginBytes })
        assertEquals(listOf(4, 4, 2), requests.map { it.expectedPayloadBytes })
        assertTrue(requests.all { it.peerId == 7L && it.piece == 10L })
        assertEquals(1, coordinator.trackedPieceCount())
    }

    @Test
    fun chokedPeerReceivesNoWorkUntilUnchokedEvent() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = false))

        assertTrue(coordinator.schedule(10, 20, nowMillis = 0).isEmpty())

        coordinator.onPeerEvent(AceLivePeerChokeChanged(peerId = 1, unchoked = true))
        assertEquals(3, coordinator.schedule(10, 20, nowMillis = 1).size)
    }

    @Test
    fun droppedPeerRequeuesPieceForAnotherActivePeer() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.onPeerEvent(window(peerId = 2, min = 10, max = 20, unchoked = true))

        assertTrue(coordinator.schedule(10, 20, nowMillis = 0).all { it.peerId == 1L })
        assertEquals(1, coordinator.trackedPieceCount())

        val dropped = coordinator.onPeerEvent(AceLivePeerDropped(peerId = 1))
        assertEquals(listOf(10L), dropped.requeuedPieces)
        assertEquals(0, coordinator.trackedPieceCount())

        val reassigned = coordinator.schedule(10, 20, nowMillis = 1)
        assertTrue(reassigned.all { it.peerId == 2L })
    }

    @Test
    fun allExpectedChunksCompletePieceWithoutAdvancingContiguousCursor() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1_782_925_464.8243976)

        assertEquals(
            AceLiveChunkDisposition.ACCEPTED,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 0, header = header, size = 4), 10)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.ACCEPTED,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 1, header = header, size = 4), 10)
                .disposition
        )
        val completed = coordinator.onChunk(
            chunk(peer = 1, piece = 10, index = 2, header = header, size = 2),
            nextNeeded = 10
        )

        assertEquals(AceLiveChunkDisposition.PIECE_COMPLETED, completed.disposition)
        assertTrue(completed.pieceCompleted)
        assertNull(coordinator.ownerOf(10))
        assertEquals(0, coordinator.trackedPieceCount())

        coordinator.onCursorAdvanced(11, nowMillis = 1)
        assertEquals(3, coordinator.schedule(11, 20, nowMillis = 1).size)
    }

    @Test
    fun duplicateChunkDoesNotCompletePieceTwice() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)
        val first = chunk(peer = 1, piece = 10, index = 0, header = header, size = 4)

        assertEquals(AceLiveChunkDisposition.ACCEPTED, coordinator.onChunk(first, 10).disposition)
        assertEquals(AceLiveChunkDisposition.DUPLICATE, coordinator.onChunk(first, 10).disposition)
        assertEquals(1L, coordinator.ownerOf(10))
    }

    @Test
    fun incomingChunkMustMatchCurrentOwnerAndStream() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)

        assertEquals(
            AceLiveChunkDisposition.WRONG_PEER,
            coordinator.onChunk(chunk(peer = 2, piece = 10, index = 0, header = header, size = 4), 10)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.WRONG_STREAM,
            coordinator.onChunk(
                AceLiveIncomingChunk(
                    peerId = 1,
                    streamIndex = 1,
                    piece = 10,
                    chunkIndex = 0,
                    pieceHeader = header,
                    data = ByteArray(4)
                ),
                nextNeeded = 10
            ).disposition
        )
    }

    @Test
    fun staleFarFutureAndUnsolicitedChunksAreRejectedBeforeTracking() {
        val coordinator = coordinator(maxInFlightPerPeer = 1, maxAhead = 2)
        coordinator.onPeerEvent(window(peerId = 1, min = 100, max = 200, unchoked = true))
        coordinator.schedule(100, 200, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)

        assertEquals(
            AceLiveChunkDisposition.STALE,
            coordinator.onChunk(chunk(peer = 1, piece = 99, index = 0, header = header, size = 4), 100)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.TOO_FAR_AHEAD,
            coordinator.onChunk(chunk(peer = 1, piece = 102, index = 0, header = header, size = 4), 100)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.UNSOLICITED,
            coordinator.onChunk(chunk(peer = 1, piece = 101, index = 0, header = header, size = 4), 100)
                .disposition
        )
    }

    @Test
    fun pieceHeaderMustStayIdenticalAcrossChunks() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)

        val firstHeader = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)
        val secondHeader = AceLivePieceHeaderCodec.encodeUnixSeconds(1001.0)
        assertEquals(
            AceLiveChunkDisposition.ACCEPTED,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 0, header = firstHeader, size = 4), 10)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.PIECE_HEADER_MISMATCH,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 1, header = secondHeader, size = 4), 10)
                .disposition
        )
    }

    @Test
    fun invalidChunkGeometryDoesNotMutateCompletionState() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)

        assertEquals(
            AceLiveChunkDisposition.INVALID_CHUNK_INDEX,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 3, header = header, size = 1), 10)
                .disposition
        )
        assertEquals(
            AceLiveChunkDisposition.INVALID_PAYLOAD_SIZE,
            coordinator.onChunk(chunk(peer = 1, piece = 10, index = 0, header = header, size = 3), 10)
                .disposition
        )
        assertEquals(1L, coordinator.ownerOf(10))
    }

    @Test
    fun recoveryTimeoutForgetsChunkTrackingBeforePieceIsReassigned() {
        val coordinator = coordinator(maxInFlightPerPeer = 1)
        coordinator.onPeerEvent(window(peerId = 1, min = 10, max = 20, unchoked = true))
        coordinator.schedule(10, 20, nowMillis = 0)
        assertEquals(1, coordinator.trackedPieceCount())

        val plan = coordinator.evaluateRecovery(10, nowMillis = 4_000)

        assertEquals(listOf(10L), plan.timedOutRequests.map { it.piece })
        assertEquals(0, coordinator.trackedPieceCount())
        assertNull(coordinator.ownerOf(10))
        assertEquals(3, coordinator.schedule(10, 20, nowMillis = 4_000).size)
    }

    @Test
    fun schedulingNeverRunsBeyondReassemblerAcceptanceHorizon() {
        val coordinator = coordinator(maxInFlightPerPeer = 4, maxAhead = 2)
        coordinator.onPeerEvent(window(peerId = 1, min = 100, max = 200, unchoked = true))

        val requests = coordinator.schedule(100, 200, nowMillis = 0)

        assertEquals(listOf(100L, 101L), requests.map { it.piece }.distinct())
        assertEquals(6, requests.size)
        assertNull(coordinator.ownerOf(102))
    }

    @Test(expected = IllegalArgumentException::class)
    fun geometryWithMoreThanU16ChunkSpaceIsRejected() {
        AceLiveActivePeerCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 65_537,
                chunkLengthBytes = 1,
                bitrate = 1
            ),
            maxInFlightPerPeer = 1
        )
    }

    private fun coordinator(
        maxInFlightPerPeer: Int,
        maxAhead: Long = 512
    ) = AceLiveActivePeerCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        maxInFlightPerPeer = maxInFlightPerPeer,
        maxReassemblerAheadPieces = maxAhead
    )

    private fun window(
        peerId: Long,
        min: Long,
        max: Long,
        unchoked: Boolean
    ) = AceLivePeerWindowUpdated(
        AceLivePeerWindow(
            peerId = peerId,
            minPiece = min,
            maxPiece = max,
            unchoked = unchoked
        )
    )

    private fun chunk(
        peer: Long,
        piece: Long,
        index: Int,
        header: ByteArray,
        size: Int
    ) = AceLiveIncomingChunk(
        peerId = peer,
        streamIndex = 0,
        piece = piece,
        chunkIndex = index,
        pieceHeader = header,
        data = ByteArray(size)
    )
}
