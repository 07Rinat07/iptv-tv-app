package com.iptv.tv.core.p2p

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveProducerMediaBoundaryQualificationTest {
    @Test
    fun `emitted piece crosses auth resync media output and marks verified peer producing`() {
        val boundaryMessages = mutableListOf<String>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { status, message ->
                assertEquals("embedded_ace_live_producer_boundary", status)
                boundaryMessages += message
            }
        )
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = PIECE_NUMBER,
            maxInFlightPerPeer = 1,
            producerBoundaryDiagnostics = reporter
        )
        val authenticator = AceLiveMediaAuthenticator(publicKeyDer = null)
        val resynchronizer = AceLiveMpegTsResynchronizer()
        val discontinuityGate = AceLiveTsDiscontinuityGate()
        val mediaBuffer = AceLiveMediaBuffer(maxBufferedBytes = TS_PACKET_BYTES * 32)
        val productionTracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = PRODUCING_FRESHNESS_MILLIS
        )
        val tsPayload = transportStreamPayload()
        val signatureTail = ByteArray(SIGNATURE_BYTES) { index -> (0x60 + index).toByte() }
        val emittedPiece = AceLiveReassembledPiece(
            piece = PIECE_NUMBER,
            pieceHeader = AceLivePieceHeaderCodec.encodeUnixSeconds(1_700_000_000.25),
            data = tsPayload + signatureTail,
            sourcePeerId = PEER_ID
        )

        try {
            productionTracker.onTransportConnected(PEER_ID, CONNECTED_AT_MILLIS)
            productionTracker.onHandshakeAccepted(PEER_ID)
            productionTracker.onPeerRequestability(
                peerId = PEER_ID,
                windowUseful = true,
                unchoked = true
            )

            val beforeMedia = productionTracker.snapshot(MEDIA_AT_MILLIS)
            assertEquals(1, beforeMedia.connectedPeers)
            assertEquals(1, beforeMedia.handshakedPeers)
            assertEquals(1, beforeMedia.windowUsefulPeers)
            assertEquals(1, beforeMedia.unchokedPeers)
            assertEquals(0, beforeMedia.producingPeers)

            val authentication = authenticator.verifyAndStrip(emittedPiece.data)
            assertTrue(authentication is P2pResult.Success)
            val authenticatedPayload = (authentication as P2pResult.Success).data
            assertArrayEquals(tsPayload, authenticatedPayload)
            session.reportPieceAuthenticated(
                peerId = emittedPiece.sourcePeerId,
                piece = emittedPiece.piece,
                bytes = authenticatedPayload.size.toLong(),
                nowMillis = MEDIA_AT_MILLIS
            )

            val resynchronized = resynchronizer.consume(authenticatedPayload)
            assertArrayEquals(tsPayload, resynchronized)
            session.reportTsResyncOutput(
                peerId = emittedPiece.sourcePeerId,
                piece = emittedPiece.piece,
                bytes = resynchronized.size.toLong(),
                nowMillis = MEDIA_AT_MILLIS
            )

            val outputBytes = discontinuityGate.consume(resynchronized)
            assertArrayEquals(tsPayload, outputBytes)
            val acceptedBytes = mediaBuffer.append(outputBytes)
            assertEquals(tsPayload.size, acceptedBytes)
            session.reportMediaAppended(
                peerId = emittedPiece.sourcePeerId,
                piece = emittedPiece.piece,
                bytes = acceptedBytes.toLong(),
                nowMillis = MEDIA_AT_MILLIS
            )
            productionTracker.onMediaProduced(
                peerId = emittedPiece.sourcePeerId,
                mediaBytes = acceptedBytes.toLong(),
                nowMillis = MEDIA_AT_MILLIS
            )

            assertEquals(tsPayload.size, mediaBuffer.retainedBytes())
            val reader = mediaBuffer.openReader()
            val retainedOutput = ByteArray(tsPayload.size)
            assertEquals(
                retainedOutput.size,
                reader.read(retainedOutput, 0, retainedOutput.size)
            )
            assertArrayEquals(tsPayload, retainedOutput)
            reader.confirmDelivered(retainedOutput.size, nowMillis = MEDIA_AT_MILLIS + 1)

            val afterMedia = productionTracker.snapshot(MEDIA_AT_MILLIS)
            assertEquals(1, afterMedia.connectedPeers)
            assertEquals(1, afterMedia.handshakedPeers)
            assertEquals(1, afterMedia.windowUsefulPeers)
            assertEquals(1, afterMedia.unchokedPeers)
            assertEquals(1, afterMedia.producingPeers)
            assertEquals(0L, afterMedia.freshestMediaAgeMillis)

            val peer = productionTracker.peerSnapshots(MEDIA_AT_MILLIS).single()
            assertEquals(PEER_ID, peer.peerId)
            assertTrue(peer.producing)
            assertEquals(tsPayload.size.toLong(), peer.totalMediaBytes)
            assertEquals(0L, peer.mediaAgeMillis)

            assertEquals(3, boundaryMessages.size)
            assertTrue(boundaryMessages[0].contains("stage=authenticated"))
            assertTrue(boundaryMessages[1].contains("stage=ts_resync_output"))
            assertTrue(boundaryMessages[2].contains("stage=media_appended"))
            boundaryMessages.forEach { message ->
                assertTrue(message.contains("peer=$PEER_ID"))
                assertTrue(message.contains("piece=$PIECE_NUMBER"))
                assertTrue(message.contains("bytes=${tsPayload.size}"))
            }
            assertTrue(boundaryMessages.last().contains("authenticated=1"))
            assertTrue(boundaryMessages.last().contains("ts_resync_output=1"))
            assertTrue(boundaryMessages.last().contains("media_appended=1"))
            assertTrue(boundaryMessages.none { it.contains("stage=authentication_rejected") })
        } finally {
            mediaBuffer.close()
        }
    }

    private fun transportStreamPayload(): ByteArray = ByteArray(TS_PACKET_BYTES * SYNC_PACKET_COUNT) { index ->
        ((index * 17 + 3) and 0xff).toByte()
    }.also { bytes ->
        repeat(SYNC_PACKET_COUNT) { packet ->
            val offset = packet * TS_PACKET_BYTES
            bytes[offset] = TS_SYNC_BYTE
            bytes[offset + 1] = 0x1f
            bytes[offset + 2] = packet.toByte()
            bytes[offset + 3] = 0x10
        }
    }

    private companion object {
        const val PEER_ID = 77L
        const val PIECE_NUMBER = 10L
        const val TS_PACKET_BYTES = 188
        const val SYNC_PACKET_COUNT = 5
        const val SIGNATURE_BYTES = 96
        const val CONNECTED_AT_MILLIS = 1_000L
        const val MEDIA_AT_MILLIS = 1_250L
        const val PRODUCING_FRESHNESS_MILLIS = 5_000L
        val TS_SYNC_BYTE: Byte = 0x47
    }
}
