package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerSessionCoordinatorTest {
    @Test
    fun schedulingProducesOnlyVerifiedChunkRequestFrames() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 7, min = 10, max = 20, unchoked = true))

        val outbound = session.schedule(head = 10, nowMillis = 0)

        assertEquals(listOf(0, 1, 2), outbound.map { it.request.chunkIndex })
        assertTrue(outbound.all { it.request.peerId == 7L && it.request.piece == 10L })
        outbound.forEach { frame ->
            val decoded = session.decodeNext(frame.bytes) as AceLivePeerFrameDecodeResult.Decoded
            val unknownRequest = decoded.message as AceLivePeerWireMessage.Unknown
            assertEquals(6, unknownRequest.id)
            assertArrayEquals(frame.request.wirePayload(), unknownRequest.payload)
            assertEquals(frame.bytes.size, decoded.consumedBytes)
        }
    }

    @Test
    fun chokeAndUnchokeWireMessagesGateNewAssignments() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))

        session.onPeerMessage(1, AceLivePeerWireMessage.Choke, nowMillis = 0)
        assertTrue(session.schedule(head = 20, nowMillis = 0).isEmpty())

        session.onPeerMessage(1, AceLivePeerWireMessage.Unchoke, nowMillis = 1)
        assertEquals(3, session.schedule(head = 10, nowMillis = 1).size)
    }

    @Test
    fun completedFuturePieceWaitsThenBothPiecesEmitContiguously() {
        val session = session(maxInFlightPerPeer = 2)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))
        assertEquals(6, session.schedule(head = 11, nowMillis = 0).size)

        val header10 = header(1000.0)
        val header11 = header(1001.0)
        assertTrue(completePiece(session, peer = 1, piece = 11, header = header11, base = 20, now = 1)
            .emittedPieces.isEmpty())
        assertEquals(10L, session.nextNeededPiece())
        assertEquals(1, session.bufferedPieceCount())

        val completed = completePiece(
            session,
            peer = 1,
            piece = 10,
            header = header10,
            base = 10,
            now = 2
        )

        assertEquals(listOf(10L, 11L), completed.emittedPieces.map { it.piece })
        assertEquals(12L, session.nextNeededPiece())
        assertEquals(0, session.bufferedPieceCount())
        assertNull(session.ownerOf(10))
        assertNull(session.ownerOf(11))
    }

    @Test
    fun wrongPeerNeverAllocatesReassemblyBuffer() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))
        session.schedule(head = 10, nowMillis = 0)

        val result = session.onPeerMessage(
            peerId = 2,
            message = liveChunk(piece = 10, index = 0, header = header(1000.0), data = ByteArray(4)),
            nowMillis = 1
        )

        assertEquals(AceLiveChunkDisposition.WRONG_PEER, result.activeChunkDisposition)
        assertNull(result.reassemblyDisposition)
        assertEquals(0, session.bufferedPieceCount())
        assertEquals(1L, session.ownerOf(10))
    }

    @Test
    fun reassemblyHeaderPreflightRejectsBeforeActiveChunkStateMutates() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))
        session.schedule(head = 10, nowMillis = 0)
        val header = header(1000.0)

        val first = session.onPeerMessage(
            1,
            liveChunk(10, 0, header, ByteArray(4)),
            nowMillis = 1
        )
        assertEquals(AceLiveReassemblyDisposition.ACCEPTED, first.reassemblyDisposition)

        val mismatch = session.onPeerMessage(
            1,
            liveChunk(10, 1, header(1001.0), ByteArray(4)),
            nowMillis = 2
        )
        assertNull(mismatch.activeChunkDisposition)
        assertEquals(AceLiveReassemblyDisposition.PIECE_HEADER_MISMATCH, mismatch.reassemblyDisposition)

        val corrected = session.onPeerMessage(
            1,
            liveChunk(10, 1, header, ByteArray(4)),
            nowMillis = 3
        )
        assertEquals(AceLiveChunkDisposition.ACCEPTED, corrected.activeChunkDisposition)
        assertEquals(AceLiveReassemblyDisposition.ACCEPTED, corrected.reassemblyDisposition)
    }

    @Test
    fun peerDropDiscardsPartialBytesBeforeReassignment() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))
        session.onPeerWindow(peerWindow(id = 2, min = 10, max = 20, unchoked = true))
        session.schedule(head = 10, nowMillis = 0)
        session.onPeerMessage(
            1,
            liveChunk(10, 0, header(1000.0), ByteArray(4)),
            nowMillis = 1
        )
        assertEquals(10L, session.bufferedPayloadBytes())

        val dropped = session.onPeerDropped(1)

        assertEquals(listOf(10L), dropped.requeuedPieces)
        assertEquals(0L, session.bufferedPayloadBytes())
        assertEquals(0, session.bufferedPieceCount())
        val reassigned = session.schedule(head = 10, nowMillis = 2)
        assertTrue(reassigned.all { it.request.peerId == 2L })
    }

    @Test
    fun timeoutRequeueAlsoDiscardsPartialBytes() {
        val diagnostics = mutableListOf<String>()
        val session = session(
            maxInFlightPerPeer = 1,
            producerBoundaryDiagnostics = AceLiveProducerBoundaryDiagnosticsReporter(
                observer = { _, message -> diagnostics += message }
            )
        )
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))
        session.schedule(head = 10, nowMillis = 0)
        session.onPeerMessage(
            1,
            liveChunk(10, 0, header(1000.0), ByteArray(4)),
            nowMillis = 1
        )

        val plan = session.evaluateRecovery(nowMillis = 4_000)

        assertEquals(listOf(10L), plan.timedOutRequests.map { it.piece })
        assertEquals(0L, session.bufferedPayloadBytes())
        assertNull(session.ownerOf(10))
        assertTrue(
            diagnostics.any { message ->
                message.contains("stage=request_timeout") &&
                    message.contains("peer=1") &&
                    message.contains("piece=10")
            }
        )
    }

    @Test
    fun outputBoundaryStagesAreForwardedWithPeerPieceAndBytes() {
        val diagnostics = mutableListOf<String>()
        val session = session(
            maxInFlightPerPeer = 1,
            producerBoundaryDiagnostics = AceLiveProducerBoundaryDiagnosticsReporter(
                observer = { _, message -> diagnostics += message }
            )
        )

        session.reportPieceAuthenticated(peerId = 1, piece = 10, bytes = 10, nowMillis = 1)
        session.reportTsResyncOutput(peerId = 1, piece = 10, bytes = 188, nowMillis = 2)
        session.reportMediaAppended(peerId = 1, piece = 10, bytes = 188, nowMillis = 3)
        session.reportAuthenticationRejected(
            peerId = 2,
            piece = 11,
            disposition = "signature_mismatch",
            nowMillis = 4
        )

        assertTrue(diagnostics.any { it.contains("stage=authenticated") && it.contains("bytes=10") })
        assertTrue(diagnostics.any { it.contains("stage=ts_resync_output") && it.contains("bytes=188") })
        assertTrue(diagnostics.any { it.contains("stage=media_appended") && it.contains("bytes=188") })
        assertTrue(
            diagnostics.any {
                it.contains("stage=authentication_rejected") &&
                    it.contains("disposition=signature_mismatch")
            }
        )
    }

    @Test
    fun explicitRecoveryAdvanceMovesOwnershipAndReassemblyTogether() {
        val session = recoverySession(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 105, max = 130, unchoked = true))
        assertTrue(session.schedule(head = 130, nowMillis = 0).isEmpty())
        assertNull(session.evaluateRecovery(nowMillis = 3_000).cursorAdvance)

        val plan = session.evaluateRecovery(nowMillis = 4_000)
        val advance = requireNotNull(plan.cursorAdvance)
        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), advance)

        val applied = session.applyRecoveryAdvance(advance, nowMillis = 4_000)

        assertTrue(applied.emittedPieces.isEmpty())
        assertEquals(105L, applied.nextNeededPiece)
        assertEquals(3, session.schedule(head = 105, nowMillis = 4_000).size)
    }

    @Test
    fun recoveryAdvanceIgnoresLaggingPeerAndTargetsNearestFutureWindow() {
        val session = recoverySession(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 90, max = 99, unchoked = true))
        session.onPeerWindow(peerWindow(id = 2, min = 105, max = 130, unchoked = true))

        assertTrue(session.schedule(head = 130, nowMillis = 0).isEmpty())
        val advance = requireNotNull(session.evaluateRecovery(nowMillis = 4_000).cursorAdvance)
        assertEquals(AceLiveCursorAdvance(fromPiece = 100, toPiece = 105), advance)

        val applied = session.applyRecoveryAdvance(advance, nowMillis = 4_000)

        assertEquals(105L, applied.nextNeededPiece)
        assertEquals(5L, requireNotNull(applied.outputDiscontinuity).skippedPieces)
        val outbound = session.schedule(head = 105, nowMillis = 4_000)
        assertTrue(outbound.isNotEmpty())
        assertTrue(outbound.all { it.request.peerId == 2L && it.request.piece == 105L })
    }

    @Test
    fun memoryBudgetCapsSchedulingHorizonToWholePieceCapacity() {
        val session = session(
            maxInFlightPerPeer = 4,
            maxBufferedBytes = 20
        )
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 50, unchoked = true))

        val outbound = session.schedule(head = 50, nowMillis = 0)

        assertEquals(2L, session.effectiveMaxAheadPieces)
        assertEquals(listOf(10L, 11L), outbound.map { it.request.piece }.distinct())
        assertEquals(6, outbound.size)
    }

    @Test
    fun unknownVendorFrameDoesNotMutateSessionState() {
        val session = session(maxInFlightPerPeer = 1)
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 20, unchoked = true))

        val result = session.onPeerMessage(
            peerId = 1,
            message = AceLivePeerWireMessage.Unknown(id = 99, payload = byteArrayOf(1, 2)),
            nowMillis = 0
        )

        assertTrue(!result.handled)
        assertEquals(10L, session.nextNeededPiece())
        assertEquals(0, session.bufferedPieceCount())
    }

    private fun session(
        maxInFlightPerPeer: Int,
        maxBufferedBytes: Long = AceLivePieceReassembler.DEFAULT_MAX_BUFFERED_BYTES,
        producerBoundaryDiagnostics: AceLiveProducerBoundaryDiagnosticsReporter =
            AceLiveProducerBoundaryDiagnosticsReporter()
    ) = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxInFlightPerPeer = maxInFlightPerPeer,
        maxBufferedBytes = maxBufferedBytes,
        producerBoundaryDiagnostics = producerBoundaryDiagnostics
    )

    private fun recoverySession(
        maxInFlightPerPeer: Int
    ) = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 100,
        maxInFlightPerPeer = maxInFlightPerPeer
    )

    private fun peerWindow(
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

    private fun completePiece(
        session: AceLivePeerSessionCoordinator,
        peer: Long,
        piece: Long,
        header: ByteArray,
        base: Int,
        now: Long
    ): AceLivePeerMessageResult {
        session.onPeerMessage(
            peer,
            liveChunk(
                piece,
                0,
                header,
                byteArrayOf(base.toByte(), (base + 1).toByte(), (base + 2).toByte(), (base + 3).toByte())
            ),
            now
        )
        session.onPeerMessage(
            peer,
            liveChunk(
                piece,
                1,
                header,
                byteArrayOf((base + 4).toByte(), (base + 5).toByte(), (base + 6).toByte(), (base + 7).toByte())
            ),
            now
        )
        return session.onPeerMessage(
            peer,
            liveChunk(
                piece,
                2,
                header,
                byteArrayOf((base + 8).toByte(), (base + 9).toByte())
            ),
            now
        )
    }

    private fun liveChunk(
        piece: Long,
        index: Int,
        header: ByteArray,
        data: ByteArray
    ) = AceLivePeerWireMessage.LiveChunk(
        streamIndex = 0,
        piece = piece,
        pieceHeader = header,
        chunkIndex = index,
        data = data
    )

    private fun header(seconds: Double): ByteArray =
        AceLivePieceHeaderCodec.encodeUnixSeconds(seconds)
}
