package com.iptv.tv.core.p2p

import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePieceTimeoutTelemetryTest {
    @Test
    fun requestTimeoutReportsPartialChunkProgressAndLeaseAges() {
        val diagnostics = mutableListOf<String>()
        val session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxInFlightPerPeer = 1,
            producerBoundaryDiagnostics = AceLiveProducerBoundaryDiagnosticsReporter(
                observer = { _, message -> diagnostics += message }
            )
        )
        session.onPeerWindow(
            AceLivePeerWindow(
                peerId = 1,
                minPiece = 10,
                maxPiece = 20,
                unchoked = true
            )
        )
        session.schedule(head = 10, nowMillis = 0)
        session.onPeerMessage(
            peerId = 1,
            message = AceLivePeerWireMessage.LiveChunk(
                streamIndex = 0,
                piece = 10,
                pieceHeader = AceLivePieceHeaderCodec.encodeUnixSeconds(1000.0),
                chunkIndex = 0,
                data = ByteArray(4)
            ),
            nowMillis = 1
        )

        session.evaluateRecovery(nowMillis = 4_001)

        val timeout = diagnostics.single { it.contains("stage=request_timeout") }
        assertTrue(timeout.contains("accepted_chunks=1"))
        assertTrue(timeout.contains("chunks_per_piece=3"))
        assertTrue(timeout.contains("assignment_age_ms=4001"))
        assertTrue(timeout.contains("progress_age_ms=4000"))
    }
}
