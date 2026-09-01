package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTransportRaceDiagnosticsReporterTest {
    private val context = AceLiveRuntimeDiagnosticsContext(
        startupId = 101L,
        runtimeId = 7L,
        generation = 3L,
        path = "direct_retry"
    )
    private val deterministicFingerprinter = AceLiveEndpointFingerprinter { host, port ->
        hmacEndpointFingerprint(TEST_KEY, host, port)
    }

    @Test
    fun `race metric is forwarded and persisted with opaque endpoint fingerprint`() {
        val forwarded = mutableListOf<P2pRuntimeMetric>()
        val observed = mutableListOf<Pair<String, String>>()
        val metric = raceMetric()
        val reporter = AceLiveTransportRaceDiagnosticsReporter(
            observer = { status, message -> observed += status to message },
            context = context,
            delegate = P2pRuntimeMetricsReporter(forwarded::add),
            endpointFingerprinter = deterministicFingerprinter
        )

        reporter.report(metric)

        assertEquals(1, forwarded.size)
        assertSame(metric, forwarded.single())
        assertEquals(1, observed.size)
        assertEquals("embedded_ace_live_transport_race", observed.single().first)
        val message = observed.single().second
        assertEquals(
            "winner=utp elapsed_ms=40 endpoint_fp=4470886ad6c012c6bade " +
                "tcp_connected_ms=10 tcp_outcome=handshake_rejected tcp_terminal_ms=20 " +
                "utp_connected_ms=25 utp_outcome=qualified_winner utp_terminal_ms=40 " +
                "startup_id=101 runtime_id=7 generation=3 path=direct_retry",
            message
        )
        assertFalse(message.contains(metric.endpointHost))
        assertFalse(message.contains("endpoint_host"))
        assertFalse(message.contains("endpoint_port"))
        assertFalse(message.contains(metric.endpointPort.toString()))
    }

    @Test
    fun `endpoint fingerprint is stable only for the same normalized endpoint and key`() {
        val first = hmacEndpointFingerprint(TEST_KEY, "Peer.Example.COM ", 8621)
        val same = hmacEndpointFingerprint(TEST_KEY, "peer.example.com", 8621)
        val differentPort = hmacEndpointFingerprint(TEST_KEY, "peer.example.com", 8622)
        val differentKey = hmacEndpointFingerprint(ByteArray(32) { 7 }, "peer.example.com", 8621)

        assertEquals(first, same)
        assertEquals(20, first.length)
        assertTrue(first.all { it in "0123456789abcdef" })
        assertNotEquals(first, differentPort)
        assertNotEquals(first, differentKey)
    }

    @Test
    fun `non race metrics stay on delegate only`() {
        val forwarded = mutableListOf<P2pRuntimeMetric>()
        var observerCalled = false
        val reporter = AceLiveTransportRaceDiagnosticsReporter(
            observer = { _, _ -> observerCalled = true },
            context = context,
            delegate = P2pRuntimeMetricsReporter(forwarded::add),
            endpointFingerprinter = deterministicFingerprinter
        )
        val metric = P2pRuntimeMetric.FirstByteReady(
            sourceType = "magnet",
            elapsedMillis = 12L,
            positionBytes = 0L,
            byteCount = 188
        )

        reporter.report(metric)

        assertSame(metric, forwarded.single())
        assertFalse(observerCalled)
    }

    @Test
    fun `observer failure is isolated after delegate delivery`() {
        val forwarded = mutableListOf<P2pRuntimeMetric>()
        val metric = raceMetric()
        val reporter = AceLiveTransportRaceDiagnosticsReporter(
            observer = { _, _ -> error("diagnostics storage unavailable") },
            context = context,
            delegate = P2pRuntimeMetricsReporter(forwarded::add),
            endpointFingerprinter = deterministicFingerprinter
        )

        val result = runCatching { reporter.report(metric) }

        assertTrue(result.isSuccess)
        assertSame(metric, forwarded.single())
    }

    private fun raceMetric() = AceLiveTransportRaceMetric(
        elapsedMillis = 40L,
        endpointHost = "203.0.113.9",
        endpointPort = 8621,
        winner = AceLiveTransportKind.UTP,
        candidates = listOf(
            AceLiveTransportCandidateMetric(
                transport = AceLiveTransportKind.UTP,
                physicalConnectedMillis = 25L,
                outcome = AceLiveTransportCandidateOutcome.QUALIFIED_WINNER,
                terminalElapsedMillis = 40L
            ),
            AceLiveTransportCandidateMetric(
                transport = AceLiveTransportKind.TCP,
                physicalConnectedMillis = 10L,
                outcome = AceLiveTransportCandidateOutcome.HANDSHAKE_REJECTED,
                terminalElapsedMillis = 20L
            )
        )
    )

    private companion object {
        val TEST_KEY = ByteArray(32) { index -> index.toByte() }
    }
}
