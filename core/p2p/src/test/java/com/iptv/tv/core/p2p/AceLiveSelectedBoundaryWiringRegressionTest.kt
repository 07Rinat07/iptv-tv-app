package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveSelectedBoundaryWiringRegressionTest {
    @Test
    fun selectedOutboundRequestIsReportedAtProducerBoundary() {
        val diagnostics = mutableListOf<String>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { status, message -> diagnostics += "$status $message" },
            periodicIntervalMillis = 60_000L
        )
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxInFlightPerPeer = 1,
            producerBoundaryDiagnostics = reporter
        )
        val connection = AceLivePeerConnectionStateMachine(peerId = 7, session = session)
        connection.onTransportConnected()
        connection.onHandshakeAccepted()
        connection.consumePeerBytes(
            frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10ee")) + frame(id = 1),
            nowMillis = 0
        )

        val scheduled = session.schedule(head = 12, nowMillis = 1)
        val selected = connection.selectOutboundRequestFrames(scheduled)

        assertEquals(3, selected.size)
        assertTrue(
            "selected request frames must cross the selected producer-boundary telemetry stage",
            diagnostics.any { line ->
                line.contains("embedded_ace_live_producer_boundary") &&
                    line.contains("stage=selected")
            }
        )
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
