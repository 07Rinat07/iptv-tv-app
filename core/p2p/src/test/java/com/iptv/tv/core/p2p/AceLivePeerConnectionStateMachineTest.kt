package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerConnectionStateMachineTest {
    @Test
    fun acceptedHandshakeEmitsInterestedAndRequiresWindowPlusUnchoke() {
        val session = session()
        val connection = AceLivePeerConnectionStateMachine(peerId = 7, session = session)

        connection.onTransportConnected()
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2), connection.onHandshakeAccepted())
        assertFalse(connection.isReadyForRequests())

        val metadata = frame(
            id = 20,
            payload = byteArrayOf(0) + ascii(
                "d2:mid9:max_piecei12e9:min_piecei10e8:positioni12eee"
            )
        )
        val metadataResult = connection.consumePeerBytes(metadata, nowMillis = 0)

        assertEquals(1, metadataResult.decodedFrames)
        assertEquals(12L, connection.advertisedHead())
        assertFalse(connection.isReadyForRequests())
        assertTrue(session.schedule(head = 12, nowMillis = 0).isEmpty())

        connection.consumePeerBytes(frame(id = 1), nowMillis = 1)
        assertTrue(connection.isPeerUnchoked())
        assertTrue(connection.isReadyForRequests())

        val scheduled = session.schedule(head = 12, nowMillis = 1)
        val selected = connection.selectOutboundRequestFrames(scheduled)
        assertEquals(3, selected.size)
        assertTrue(scheduled.all { it.request.peerId == 7L && it.request.piece == 10L })
    }

    @Test
    fun partialMetadataFrameIsBufferedUntilComplete() {
        val session = session()
        val connection = connected(connectionSession = session)
        val frame = frame(
            id = 99,
            payload = ascii("d9:max_piecei10e9:min_piecei10ee")
        )

        val first = connection.consumePeerBytes(frame.copyOfRange(0, 3), nowMillis = 0)
        val second = connection.consumePeerBytes(frame.copyOfRange(3, frame.size), nowMillis = 1)

        assertEquals(0, first.decodedFrames)
        assertTrue(first.metadataUpdates.isEmpty())
        assertEquals(1, second.decodedFrames)
        assertEquals(10L, second.metadataUpdates.single().maxPiece)
    }

    @Test
    fun chokeStopsOutboundSelectionWithoutDroppingWindow() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10ee")) + frame(id = 1),
            nowMillis = 0
        )
        val scheduled = session.schedule(head = 12, nowMillis = 1)
        assertEquals(3, connection.selectOutboundRequestFrames(scheduled).size)

        connection.consumePeerBytes(frame(id = 0), nowMillis = 2)

        assertFalse(connection.isReadyForRequests())
        assertTrue(connection.selectOutboundRequestFrames(scheduled).isEmpty())
        assertEquals(12L, connection.advertisedHead())
    }

    @Test
    fun disconnectRequeuesOwnedPiecesAndResetsConnectionState() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10ee")) + frame(id = 1),
            nowMillis = 0
        )
        session.schedule(head = 10, nowMillis = 1)
        assertEquals(7L, session.ownerOf(10))

        val dropped = connection.onTransportDisconnected()

        assertEquals(listOf(10L), dropped.requeuedPieces)
        assertEquals(AceLivePeerConnectionPhase.DISCONNECTED, connection.phase())
        assertFalse(connection.isPeerUnchoked())
        assertEquals(null, connection.advertisedHead())
        assertEquals(null, session.ownerOf(10))
    }

    @Test
    fun oversizedFrameRecommendsDisconnectAndBlocksFurtherRequests() {
        val session = session(wireCodec = AceLivePeerWireCodec(maxFrameLengthBytes = 16))
        val connection = connected(session)
        val hostilePrefix = byteArrayOf(0, 0, 0, 17)

        val result = connection.consumePeerBytes(hostilePrefix, nowMillis = 0)

        assertTrue(result.disconnectRecommended)
        assertFalse(connection.isReadyForRequests())
        assertTrue(connection.consumePeerBytes(frame(id = 1), nowMillis = 1).disconnectRecommended)
    }

    @Test
    fun malformedMetadataIsReportedWithoutKillingFramedConnection() {
        val session = session()
        val connection = connected(session)

        val malformed = connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei10e")),
            nowMillis = 0
        )
        val keepAlive = connection.consumePeerBytes(byteArrayOf(0, 0, 0, 0), nowMillis = 1)

        assertEquals(
            listOf(AceLivePeerMetadataRejectReason.MALFORMED_BENCODE),
            malformed.metadataRejections
        )
        assertFalse(malformed.disconnectRecommended)
        assertEquals(1, keepAlive.decodedFrames)
        assertFalse(keepAlive.disconnectRecommended)
    }

    private fun connected(connectionSession: AceLivePeerSessionCoordinator): AceLivePeerConnectionStateMachine {
        val connection = AceLivePeerConnectionStateMachine(peerId = 7, session = connectionSession)
        connection.onTransportConnected()
        connection.onHandshakeAccepted()
        return connection
    }

    private fun session(
        wireCodec: AceLivePeerWireCodec = AceLivePeerWireCodec()
    ) = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxInFlightPerPeer = 1,
        wireCodec = wireCodec
    )

    private fun frame(id: Int, payload: ByteArray = byteArrayOf()): ByteArray {
        val bodyLength = 1 + payload.size
        return byteArrayOf(
            (bodyLength ushr 24).toByte(),
            (bodyLength ushr 16).toByte(),
            (bodyLength ushr 8).toByte(),
            bodyLength.toByte(),
            id.toByte()
        ) + payload
    }

    private fun ascii(value: String): ByteArray = value.toByteArray(Charsets.US_ASCII)
}
