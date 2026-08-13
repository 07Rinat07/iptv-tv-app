package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveStartupCursorRebaseTest {
    @Test
    fun `later verified window rebases an evicted startup cursor before media output`() {
        val session = session()
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 30))

        assertEquals(
            16L,
            session.initializeFromLiveWindow(
                window = advertisedWindow(min = 10, max = 30, position = 20),
                prefetchPieces = 4,
                nowMillis = 0
            )
        )
        assertTrue(session.schedule(head = 30, nowMillis = 1).isNotEmpty())
        assertEquals(1L, session.ownerOf(16))

        session.onPeerWindow(peerWindow(id = 1, min = 40, max = 60))
        val rebased = session.initializeFromLiveWindow(
            window = advertisedWindow(min = 40, max = 60, position = 50),
            prefetchPieces = 4,
            nowMillis = 2
        )

        assertEquals(46L, rebased)
        assertEquals(46L, session.nextNeededPiece())
        assertNull(session.ownerOf(16))
    }

    @Test
    fun `later verified window cannot rebase after contiguous media was emitted`() {
        val session = session()
        session.onPeerWindow(peerWindow(id = 1, min = 10, max = 30))
        session.initializeFromLiveWindow(
            window = advertisedWindow(min = 10, max = 30, position = 20),
            prefetchPieces = 4,
            nowMillis = 0
        )
        session.schedule(head = 16, nowMillis = 1)

        val header = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0)
        session.onPeerMessage(1, liveChunk(16, 0, header, byteArrayOf(1, 2, 3, 4)), nowMillis = 2)
        session.onPeerMessage(1, liveChunk(16, 1, header, byteArrayOf(5, 6, 7, 8)), nowMillis = 2)
        val completed = session.onPeerMessage(
            1,
            liveChunk(16, 2, header, byteArrayOf(9, 10)),
            nowMillis = 2
        )
        assertEquals(listOf(16L), completed.emittedPieces.map { it.piece })
        assertEquals(17L, session.nextNeededPiece())

        session.onPeerWindow(peerWindow(id = 1, min = 40, max = 60))
        val retained = session.initializeFromLiveWindow(
            window = advertisedWindow(min = 40, max = 60, position = 50),
            prefetchPieces = 4,
            nowMillis = 3
        )

        assertEquals(17L, retained)
        assertEquals(17L, session.nextNeededPiece())
    }

    private fun session() = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxInFlightPerPeer = 1
    )

    private fun peerWindow(id: Long, min: Long, max: Long) = AceLivePeerWindow(
        peerId = id,
        minPiece = min,
        maxPiece = max,
        unchoked = true
    )

    private fun advertisedWindow(min: Long, max: Long, position: Long) =
        AceLivePeerAdvertisedWindow(
            minPiece = min,
            maxPiece = max,
            position = position,
            distanceFromSource = null,
            minPieceExplicit = true
        )

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
}
