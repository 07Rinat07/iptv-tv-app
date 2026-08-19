package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerConnectionStateMachineAdversarialTest {
    @Test
    fun foreignStreamHaveMustNotAdvancePrimaryLiveWindow() {
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxInFlightPerPeer = 1
        )
        val connection = AceLivePeerConnectionStateMachine(peerId = 7, session = session)
        connection.onTransportConnected()
        connection.onHandshakeAccepted()

        connection.consumePeerBytes(
            frame(
                id = 99,
                payload = ascii("d9:max_piecei12e9:min_piecei10e8:positioni12ee")
            ) + frame(id = 1),
            nowMillis = 0
        )
        assertEquals(12L, connection.advertisedHead())

        val foreignStreamHave = connection.consumePeerBytes(
            frame(
                id = 10,
                payload = byteArrayOf(
                    0, 0, 0, 1, // streamIndex = 1, while this session requests stream 0 only
                    0, 0, 0, 13 // piece = 13
                )
            ),
            nowMillis = 1
        )

        assertTrue(foreignStreamHave.metadataUpdates.isEmpty())
        assertEquals(12L, connection.advertisedHead())
    }

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
