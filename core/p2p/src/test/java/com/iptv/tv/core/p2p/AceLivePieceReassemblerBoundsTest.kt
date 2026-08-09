package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePieceReassemblerBoundsTest {
    @Test
    fun secondPieceAllocationIsRejectedWhenByteBudgetIsFull() {
        val reassembler = reassembler(maxBufferedBytes = 10)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)

        assertEquals(
            AceLiveReassemblyDisposition.ACCEPTED,
            reassembler.appendAcceptedChunk(chunk(piece = 10, index = 0, header = header, size = 4)).disposition
        )
        assertEquals(10L, reassembler.bufferedPayloadBytes())

        val result = reassembler.appendAcceptedChunk(
            chunk(piece = 11, index = 0, header = header, size = 4)
        )

        assertEquals(AceLiveReassemblyDisposition.BUFFER_LIMIT_REACHED, result.disposition)
        assertEquals(listOf(10L), reassembler.bufferedPieces())
        assertEquals(10L, reassembler.bufferedPayloadBytes())
    }

    @Test
    fun explicitSkipReleasesMemoryForNextPiece() {
        val reassembler = reassembler(maxBufferedBytes = 10)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)
        reassembler.appendAcceptedChunk(chunk(piece = 10, index = 0, header = header, size = 4))

        assertTrue(reassembler.skipTo(11).isEmpty())
        assertEquals(0L, reassembler.bufferedPayloadBytes())

        val result = reassembler.appendAcceptedChunk(
            chunk(piece = 11, index = 0, header = header, size = 4)
        )
        assertEquals(AceLiveReassemblyDisposition.ACCEPTED, result.disposition)
        assertEquals(10L, reassembler.bufferedPayloadBytes())
    }

    @Test(expected = IllegalArgumentException::class)
    fun constructorRejectsPieceLargerThanBufferBudget() {
        reassembler(maxBufferedBytes = 9)
    }

    @Test(expected = IllegalArgumentException::class)
    fun geometryWithMoreThanU16ChunkSpaceIsRejected() {
        AceLivePieceReassembler(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 65_537,
                chunkLengthBytes = 1,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxBufferedBytes = 131_072
        )
    }

    private fun reassembler(maxBufferedBytes: Long) = AceLivePieceReassembler(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxBufferedBytes = maxBufferedBytes
    )

    private fun chunk(
        piece: Long,
        index: Int,
        header: ByteArray,
        size: Int
    ) = AceLiveIncomingChunk(
        peerId = 1,
        streamIndex = 0,
        piece = piece,
        chunkIndex = index,
        pieceHeader = header,
        data = ByteArray(size)
    )
}
