package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePieceReassemblerTest {
    @Test
    fun outOfOrderChunksEmitOnePieceInGeometryOrder() {
        val reassembler = reassembler(nextNeeded = 10)
        val header = header(1000.0)

        assertTrue(reassembler.appendAcceptedChunk(chunk(10, 2, header, byteArrayOf(9, 10))).emittedPieces.isEmpty())
        assertTrue(reassembler.appendAcceptedChunk(chunk(10, 0, header, byteArrayOf(1, 2, 3, 4))).emittedPieces.isEmpty())
        val result = reassembler.appendAcceptedChunk(chunk(10, 1, header, byteArrayOf(5, 6, 7, 8)))

        assertEquals(AceLiveReassemblyDisposition.ACCEPTED, result.disposition)
        assertEquals(1, result.emittedPieces.size)
        assertEquals(10L, result.emittedPieces.single().piece)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10), result.emittedPieces.single().data)
        assertArrayEquals(header, result.emittedPieces.single().pieceHeader)
        assertEquals(11L, result.nextNeededPiece)
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test
    fun completedFuturePieceWaitsForAuthoritativeCursorThenDrainsContiguously() {
        val reassembler = reassembler(nextNeeded = 10)
        val header10 = header(1000.0)
        val header11 = header(1001.0)

        completePiece(reassembler, piece = 11, header = header11, base = 20).also { result ->
            assertTrue(result.emittedPieces.isEmpty())
        }
        assertEquals(listOf(11L), reassembler.bufferedPieces())
        assertEquals(10L, reassembler.nextNeededPiece())

        val result = completePiece(reassembler, piece = 10, header = header10, base = 10)

        assertEquals(listOf(10L, 11L), result.emittedPieces.map { it.piece })
        assertArrayEquals(expectedPieceBytes(10), result.emittedPieces[0].data)
        assertArrayEquals(expectedPieceBytes(20), result.emittedPieces[1].data)
        assertEquals(12L, result.nextNeededPiece)
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test
    fun duplicateChunkIsIdempotentAndDoesNotCompleteEarly() {
        val reassembler = reassembler(nextNeeded = 10)
        val header = header(1000.0)
        val first = chunk(10, 0, header, byteArrayOf(1, 2, 3, 4))

        assertEquals(AceLiveReassemblyDisposition.ACCEPTED, reassembler.appendAcceptedChunk(first).disposition)
        val duplicate = reassembler.appendAcceptedChunk(first)

        assertEquals(AceLiveReassemblyDisposition.DUPLICATE, duplicate.disposition)
        assertTrue(duplicate.emittedPieces.isEmpty())
        assertEquals(1, reassembler.bufferedPieceCount())
        assertEquals(10L, reassembler.nextNeededPiece())
    }

    @Test
    fun staleAndFarFutureChunksAreRejectedBeforeBufferAllocation() {
        val reassembler = reassembler(nextNeeded = 100, maxAhead = 2)
        val header = header(1000.0)

        assertEquals(
            AceLiveReassemblyDisposition.STALE,
            reassembler.appendAcceptedChunk(chunk(99, 0, header, ByteArray(4))).disposition
        )
        assertEquals(
            AceLiveReassemblyDisposition.TOO_FAR_AHEAD,
            reassembler.appendAcceptedChunk(chunk(102, 0, header, ByteArray(4))).disposition
        )
        assertEquals(0, reassembler.bufferedPieceCount())
        assertEquals(100L, reassembler.nextNeededPiece())
    }

    @Test
    fun invalidTailSizeDoesNotAllocatePieceBuffer() {
        val reassembler = reassembler(nextNeeded = 10)
        val result = reassembler.appendAcceptedChunk(
            chunk(10, 2, header(1000.0), byteArrayOf(1, 2, 3, 4))
        )

        assertEquals(AceLiveReassemblyDisposition.INVALID_PAYLOAD_SIZE, result.disposition)
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test
    fun invalidChunkIndexDoesNotAllocatePieceBuffer() {
        val reassembler = reassembler(nextNeeded = 10)
        val result = reassembler.appendAcceptedChunk(
            chunk(10, 3, header(1000.0), byteArrayOf(1))
        )

        assertEquals(AceLiveReassemblyDisposition.INVALID_CHUNK_INDEX, result.disposition)
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test
    fun invalidPieceHeaderDoesNotAllocatePieceBuffer() {
        val reassembler = reassembler(nextNeeded = 10)
        val result = reassembler.appendAcceptedChunk(
            chunk(10, 0, byteArrayOf(1, 2, 3), ByteArray(4))
        )

        assertEquals(AceLiveReassemblyDisposition.INVALID_HEADER, result.disposition)
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test
    fun pieceHeaderMustRemainIdenticalAcrossChunks() {
        val reassembler = reassembler(nextNeeded = 10)
        val firstHeader = header(1000.0)
        val differentHeader = header(1001.0)

        reassembler.appendAcceptedChunk(chunk(10, 0, firstHeader, ByteArray(4)))
        val result = reassembler.appendAcceptedChunk(chunk(10, 1, differentHeader, ByteArray(4)))

        assertEquals(AceLiveReassemblyDisposition.PIECE_HEADER_MISMATCH, result.disposition)
        assertTrue(result.emittedPieces.isEmpty())
        assertEquals(1, reassembler.bufferedPieceCount())
    }

    @Test
    fun explicitSkipDropsOlderPartialPieceAndCanEmitCompletedDestination() {
        val reassembler = reassembler(nextNeeded = 10)
        val header10 = header(1000.0)
        val header11 = header(1001.0)

        reassembler.appendAcceptedChunk(chunk(10, 0, header10, ByteArray(4) { 1 }))
        completePiece(reassembler, piece = 11, header = header11, base = 20)
        assertEquals(listOf(10L, 11L), reassembler.bufferedPieces())

        val emitted = reassembler.skipTo(11)

        assertEquals(listOf(11L), emitted.map { it.piece })
        assertArrayEquals(expectedPieceBytes(20), emitted.single().data)
        assertEquals(12L, reassembler.nextNeededPiece())
        assertEquals(0, reassembler.bufferedPieceCount())
    }

    @Test(expected = IllegalArgumentException::class)
    fun skipCannotMoveCursorBackwardOrStayInPlace() {
        reassembler(nextNeeded = 10).skipTo(10)
    }

    @Test
    fun maxU32PieceEmitsOnceAndDoesNotWrapCursor() {
        val maxPiece = 0xffff_ffffL
        val reassembler = reassembler(nextNeeded = maxPiece)
        val header = header(1000.0)

        val result = completePiece(reassembler, piece = maxPiece, header = header, base = 30)

        assertEquals(listOf(maxPiece), result.emittedPieces.map { it.piece })
        assertNull(result.nextNeededPiece)
        assertNull(reassembler.nextNeededPiece())
        assertEquals(
            AceLiveReassemblyDisposition.STALE,
            reassembler.appendAcceptedChunk(chunk(maxPiece, 0, header, ByteArray(4))).disposition
        )
    }

    @Test
    fun acceptedFutureChunksRemainBoundedByConfiguredHorizon() {
        val reassembler = reassembler(nextNeeded = 50, maxAhead = 3)
        val header = header(1000.0)

        assertEquals(
            AceLiveReassemblyDisposition.ACCEPTED,
            reassembler.appendAcceptedChunk(chunk(52, 0, header, ByteArray(4))).disposition
        )
        assertEquals(
            AceLiveReassemblyDisposition.TOO_FAR_AHEAD,
            reassembler.appendAcceptedChunk(chunk(53, 0, header, ByteArray(4))).disposition
        )
        assertEquals(listOf(52L), reassembler.bufferedPieces())
    }

    @Test(expected = IllegalArgumentException::class)
    fun initialCursorMustFitU32WirePieceSpace() {
        reassembler(nextNeeded = 0x1_0000_0000L)
    }

    private fun reassembler(
        nextNeeded: Long,
        maxAhead: Long = 512
    ) = AceLivePieceReassembler(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = nextNeeded,
        maxPiecesAhead = maxAhead
    )

    private fun completePiece(
        reassembler: AceLivePieceReassembler,
        piece: Long,
        header: ByteArray,
        base: Int
    ): AceLiveReassemblyResult {
        reassembler.appendAcceptedChunk(
            chunk(piece, 0, header, byteArrayOf(base.toByte(), (base + 1).toByte(), (base + 2).toByte(), (base + 3).toByte()))
        )
        reassembler.appendAcceptedChunk(
            chunk(piece, 1, header, byteArrayOf((base + 4).toByte(), (base + 5).toByte(), (base + 6).toByte(), (base + 7).toByte()))
        )
        return reassembler.appendAcceptedChunk(
            chunk(piece, 2, header, byteArrayOf((base + 8).toByte(), (base + 9).toByte()))
        )
    }

    private fun expectedPieceBytes(base: Int): ByteArray =
        ByteArray(10) { offset -> (base + offset).toByte() }

    private fun header(seconds: Double): ByteArray =
        AceLivePieceHeaderCodec.encodeUnixSeconds(seconds)

    private fun chunk(
        piece: Long,
        index: Int,
        header: ByteArray,
        data: ByteArray
    ) = AceLiveIncomingChunk(
        peerId = 1,
        streamIndex = 0,
        piece = piece,
        chunkIndex = index,
        pieceHeader = header,
        data = data
    )
}
