package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerSessionDuplicateRegressionTest {
    @Test
    fun duplicateOfCompletedFuturePieceRemainsHarmlessAfterOwnershipRelease() {
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxInFlightPerPeer = 2
        )
        session.onPeerWindow(
            AceLivePeerWindow(
                peerId = 1,
                minPiece = 10,
                maxPiece = 20,
                unchoked = true
            )
        )
        session.schedule(head = 11, nowMillis = 0)
        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1001.0)

        session.onPeerMessage(1, chunk(piece = 11, index = 0, header = header, size = 4), 1)
        session.onPeerMessage(1, chunk(piece = 11, index = 1, header = header, size = 4), 1)
        session.onPeerMessage(1, chunk(piece = 11, index = 2, header = header, size = 2), 1)

        assertNull(session.ownerOf(11))
        assertEquals(10L, session.nextNeededPiece())
        assertEquals(1, session.bufferedPieceCount())

        val duplicate = session.onPeerMessage(
            peerId = 1,
            message = chunk(piece = 11, index = 2, header = header, size = 2),
            nowMillis = 2
        )

        assertNull(duplicate.activeChunkDisposition)
        assertEquals(AceLiveReassemblyDisposition.DUPLICATE, duplicate.reassemblyDisposition)
        assertEquals(10L, session.nextNeededPiece())
        assertEquals(1, session.bufferedPieceCount())
    }

    private fun chunk(
        piece: Long,
        index: Int,
        header: ByteArray,
        size: Int
    ) = AceLivePeerWireMessage.LiveChunk(
        streamIndex = 0,
        piece = piece,
        pieceHeader = header,
        chunkIndex = index,
        data = ByteArray(size)
    )
}
