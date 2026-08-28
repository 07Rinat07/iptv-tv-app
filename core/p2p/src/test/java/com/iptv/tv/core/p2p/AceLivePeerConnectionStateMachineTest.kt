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
    fun negotiatedPeerExchangeProducesUntrustedCandidatesAndResetsOnReconnect() {
        val session = session()
        val connection = connected(session)
        val extensionHandshake = AceBencodeEncoder.encode(
            AceBencodeValue.Dictionary(
                mapOf(
                    "m" to AceBencodeValue.Dictionary(
                        mapOf("ut_pex" to AceBencodeValue.Integer(7))
                    )
                )
            )
        )
        val negotiation = connection.consumePeerBytes(
            frame(id = 20, payload = byteArrayOf(0) + extensionHandshake),
            nowMillis = 0
        )

        assertTrue(negotiation.peerExchangeHandshakeObserved)
        assertEquals(true, negotiation.peerExchangeEnabledUpdate)
        assertEquals(7, negotiation.peerExchangeMessageId)
        assertTrue(negotiation.unknownMessageIds.isEmpty())

        val exchangePayload = AceBencodeEncoder.encode(
            AceBencodeValue.Dictionary(
                mapOf(
                    "added" to AceBencodeValue.Bytes(
                        compactPeer(8, 8, 8, 8, 8621) + compactPeer(1, 1, 1, 1, 9000)
                    )
                )
            )
        )
        val exchange = connection.consumePeerBytes(
            frame(id = 20, payload = byteArrayOf(7) + exchangePayload),
            nowMillis = 1
        )
        assertEquals(
            listOf(
                AceLiveTcpPeerEndpoint("8.8.8.8", 8621),
                AceLiveTcpPeerEndpoint("1.1.1.1", 9000)
            ),
            exchange.peerExchangePeers
        )

        connection.onTransportDisconnected()
        connection.onTransportConnected()
        connection.onHandshakeAccepted()
        val staleMapping = connection.consumePeerBytes(
            frame(id = 20, payload = byteArrayOf(7) + exchangePayload),
            nowMillis = 2
        )
        assertTrue(staleMapping.peerExchangePeers.isEmpty())
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
    fun coalescedFramesCanExceedSingleFrameCapWithoutDisconnect() {
        val session = session(wireCodec = AceLivePeerWireCodec(maxFrameLengthBytes = 16))
        val connection = connected(session)
        val maxSizedFrame = frame(id = 99, payload = ByteArray(15))
        val batch = maxSizedFrame + byteArrayOf(0, 0, 0, 0)

        val result = connection.consumePeerBytes(batch, nowMillis = 0)

        assertEquals(2, result.decodedFrames)
        assertFalse(result.disconnectRecommended)
    }

    @Test
    fun manySmallFramesInOneReadAreConsumedWithoutAggregateCap() {
        val session = session(wireCodec = AceLivePeerWireCodec(maxFrameLengthBytes = 16))
        val connection = connected(session)
        val keepAliveCount = 5_000
        val batch = ByteArray(keepAliveCount * 4)

        val result = connection.consumePeerBytes(batch, nowMillis = 0)

        assertEquals(keepAliveCount, result.decodedFrames)
        assertFalse(result.disconnectRecommended)
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
    fun windowRefreshSuppressesQueuedRequestsThatWereRequeued() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10ee")) + frame(id = 1),
            nowMillis = 0
        )
        val scheduled = session.schedule(head = 12, nowMillis = 1)
        assertEquals(3, connection.selectOutboundRequestFrames(scheduled).size)
        assertEquals(7L, session.ownerOf(10))

        val refreshed = connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei11ee")),
            nowMillis = 2
        )

        assertEquals(listOf(10L), refreshed.requeuedPieces)
        assertEquals(null, session.ownerOf(10))
        assertTrue(connection.selectOutboundRequestFrames(scheduled).isEmpty())
    }

    @Test
    fun haveAdvancesTheSlidingLiveWindow() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10e8:positioni12ee")) +
                frame(id = 1),
            nowMillis = 0
        )

        val result = connection.consumePeerBytes(
            frame(id = 4, payload = byteArrayOf(0, 0, 0, 13)),
            nowMillis = 1
        )

        val window = result.metadataUpdates.single()
        assertEquals(11L, window.minPiece)
        assertEquals(13L, window.maxPiece)
        assertEquals(13L, window.position)
        assertEquals(13L, connection.advertisedHead())
    }

    @Test
    fun compactLiveStatusRefreshesPeerAvailabilityWindow() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10e8:positioni12ee")) +
                frame(id = 1),
            nowMillis = 0
        )

        val status = compactLiveStatus(minPiece = 11, maxPiece = 13, position = 14)
        val result = connection.consumePeerBytes(frame(id = 11, payload = status), nowMillis = 1)

        val window = result.metadataUpdates.single()
        assertEquals(11L, window.minPiece)
        assertEquals(13L, window.maxPiece)
        assertEquals(14L, window.position)
        assertEquals(13L, connection.advertisedHead())
        assertTrue(connection.isReadyForRequests())
    }

    @Test
    fun streamHaveAdvancesTheSlidingLiveWindow() {
        val session = session()
        val connection = connected(session)
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10e8:positioni12ee")) +
                frame(id = 1),
            nowMillis = 0
        )

        val result = connection.consumePeerBytes(
            frame(
                id = 10,
                payload = byteArrayOf(0, 0, 0, 0, 0, 0, 0, 13)
            ),
            nowMillis = 1
        )

        val window = result.metadataUpdates.single()
        assertEquals(11L, window.minPiece)
        assertEquals(13L, window.maxPiece)
        assertEquals(13L, window.position)
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

    private fun compactPeer(a: Int, b: Int, c: Int, d: Int, port: Int): ByteArray = byteArrayOf(
        a.toByte(), b.toByte(), c.toByte(), d.toByte(),
        (port ushr 8).toByte(), port.toByte()
    )

    private fun compactLiveStatus(minPiece: Long, maxPiece: Long, position: Long): ByteArray =
        ascii(
            "d1:ai1e1:bi0e1:ci1e1:di0e1:ei1e1:fi0e" +
                "1:gi${position}e1:hi1e1:ii${minPiece}e1:ji${maxPiece}e" +
                "1:ki0e1:li1e1:mi-1e1:ni2e1:oi0e1:pi0e1:qi1e" +
                "1:ri${position}e1:si${position}e1:ti-1e1:ui1ee"
        )
}
