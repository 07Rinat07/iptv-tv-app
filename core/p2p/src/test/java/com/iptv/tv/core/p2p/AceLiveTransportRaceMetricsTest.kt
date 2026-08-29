package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTransportRaceMetricsTest {
    private val codec = AceLivePeerHandshakeCodec()
    private val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 8621)
    private val policy = AceLiveTcpConnectionPolicy(
        connectTimeoutMillis = 1_000,
        writeTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        handshakeTimeoutMillis = 1_000
    )

    @Test
    fun `wrong swarm TCP and valid uTP report typed uTP winner evidence`() = runBlocking {
        val expectedSwarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 7 }
        val localHandshake = codec.encode(
            swarmKey = expectedSwarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val wrongTcpHandshake = codec.encode(
            swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 9 },
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 2 }
        )
        val validUtpHandshake = codec.encode(
            swarmKey = expectedSwarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 3 }
        )
        val allowUtpConnect = CompletableDeferred<Unit>()
        val tcpRejected = CompletableDeferred<Unit>()
        val metrics = Collections.synchronizedList(mutableListOf<P2pRuntimeMetric>())
        val clock = AtomicLong(0L)
        val factory = AceLiveTcpUtpRacingTransportFactory(
            tcpConnect = { _, _ ->
                ScriptedTransport(
                    reads = listOf(wrongTcpHandshake),
                    onClose = { tcpRejected.complete(Unit) }
                )
            },
            utpConnect = { _, _ ->
                allowUtpConnect.await()
                ScriptedTransport(listOf(validUtpHandshake))
            },
            metricsReporter = P2pRuntimeMetricsReporter(metrics::add),
            nanoTime = { clock.addAndGet(1_000_000L) }
        )

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        tcpRejected.await()
        allowUtpConnect.complete(Unit)
        val buffer = ByteArray(128)
        raced.read(buffer)
        awaitMetric(metrics)

        assertEquals(1, metrics.size)
        val metric = metrics.single() as AceLiveTransportRaceMetric
        assertEquals(AceLiveTransportKind.UTP, metric.winner)
        assertEquals(endpoint.host, metric.endpointHost)
        assertEquals(endpoint.port, metric.endpointPort)
        assertEquals(
            AceLiveTransportCandidateOutcome.HANDSHAKE_REJECTED,
            metric.candidates.single { it.transport == AceLiveTransportKind.TCP }.outcome
        )
        assertEquals(
            AceLiveTransportCandidateOutcome.QUALIFIED_WINNER,
            metric.candidates.single { it.transport == AceLiveTransportKind.UTP }.outcome
        )
        raced.close()
    }

    @Test
    fun `all physical failures report winner none and both connect failures`() = runBlocking {
        val metrics = Collections.synchronizedList(mutableListOf<P2pRuntimeMetric>())
        val clock = AtomicLong(0L)
        val factory = AceLiveTcpUtpRacingTransportFactory(
            tcpConnect = { _, _ -> throw IOException("tcp unavailable") },
            utpConnect = { _, _ -> throw IOException("utp unavailable") },
            metricsReporter = P2pRuntimeMetricsReporter(metrics::add),
            nanoTime = { clock.addAndGet(1_000_000L) }
        )

        val result = runCatching { factory.connect(endpoint, policy) }
        awaitMetric(metrics)

        assertTrue(result.exceptionOrNull() is IOException)
        assertEquals(1, metrics.size)
        val metric = metrics.single() as AceLiveTransportRaceMetric
        assertNull(metric.winner)
        assertEquals(
            setOf(AceLiveTransportCandidateOutcome.CONNECT_FAILED),
            metric.candidates.map { it.outcome }.toSet()
        )
        assertTrue(metric.candidates.all { it.physicalConnectedMillis == null })
    }

    @Test
    fun `physical connect deadline reports typed timeout evidence`() = runBlocking {
        val never = CompletableDeferred<Unit>()
        val metrics = Collections.synchronizedList(mutableListOf<P2pRuntimeMetric>())
        val clock = AtomicLong(0L)
        val timeoutPolicy = AceLiveTcpConnectionPolicy(
            connectTimeoutMillis = 25,
            writeTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            handshakeTimeoutMillis = 1_000
        )
        val factory = AceLiveTcpUtpRacingTransportFactory(
            tcpConnect = { _, _ ->
                never.await()
                ScriptedTransport(emptyList())
            },
            utpConnect = { _, _ ->
                never.await()
                ScriptedTransport(emptyList())
            },
            metricsReporter = P2pRuntimeMetricsReporter(metrics::add),
            nanoTime = { clock.addAndGet(1_000_000L) }
        )

        val result = runCatching { factory.connect(endpoint, timeoutPolicy) }
        awaitMetric(metrics)

        assertTrue(result.exceptionOrNull() is IOException)
        val metric = metrics.single() as AceLiveTransportRaceMetric
        assertNull(metric.winner)
        assertEquals(
            setOf(AceLiveTransportCandidateOutcome.CONNECT_TIMEOUT),
            metric.candidates.map { it.outcome }.toSet()
        )
    }

    @Test
    fun `close before qualification reports cancellation without overwriting prior failure`() = runBlocking {
        val swarm = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 5 }
        val localHandshake = codec.encode(
            swarmKey = swarm,
            peerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 1 }
        )
        val metrics = Collections.synchronizedList(mutableListOf<P2pRuntimeMetric>())
        val clock = AtomicLong(0L)
        val utpFailed = CompletableDeferred<Unit>()
        val tcp = ScriptedTransport(emptyList())
        val utp = ScriptedTransport(
            reads = emptyList(),
            writeFailure = IOException("utp handshake write failed"),
            onClose = { utpFailed.complete(Unit) }
        )
        val factory = AceLiveTcpUtpRacingTransportFactory(
            tcpConnect = { _, _ -> tcp },
            utpConnect = { _, _ -> utp },
            metricsReporter = P2pRuntimeMetricsReporter(metrics::add),
            nanoTime = { clock.addAndGet(1_000_000L) }
        )

        val raced = factory.connect(endpoint, policy)
        raced.write(localHandshake)
        utpFailed.await()
        raced.close()
        awaitMetric(metrics)

        val metric = metrics.single() as AceLiveTransportRaceMetric
        assertNull(metric.winner)
        assertEquals(
            AceLiveTransportCandidateOutcome.CANCELLED_BEFORE_WINNER,
            metric.candidates.single { it.transport == AceLiveTransportKind.TCP }.outcome
        )
        assertEquals(
            AceLiveTransportCandidateOutcome.HANDSHAKE_WRITE_FAILED,
            metric.candidates.single { it.transport == AceLiveTransportKind.UTP }.outcome
        )
    }

    @Test
    fun `transport race log line is deterministic and diagnostics failures are isolated`() {
        val metric = AceLiveTransportRaceMetric(
            elapsedMillis = 40L,
            endpointHost = "192.0.2.7",
            endpointPort = 8621,
            winner = AceLiveTransportKind.TCP,
            candidates = listOf(
                AceLiveTransportCandidateMetric(
                    transport = AceLiveTransportKind.UTP,
                    physicalConnectedMillis = 20L,
                    outcome = AceLiveTransportCandidateOutcome.CANCELLED_AFTER_WINNER,
                    terminalElapsedMillis = 40L
                ),
                AceLiveTransportCandidateMetric(
                    transport = AceLiveTransportKind.TCP,
                    physicalConnectedMillis = 10L,
                    outcome = AceLiveTransportCandidateOutcome.QUALIFIED_WINNER,
                    terminalElapsedMillis = 35L
                )
            )
        )

        assertEquals(
            "event=ace_live_transport_race source=ace_live elapsed_ms=40 " +
                "endpoint_host=192.0.2.7 endpoint_port=8621 winner=tcp " +
                "tcp_connected_ms=10 tcp_outcome=qualified_winner tcp_terminal_ms=35 " +
                "utp_connected_ms=20 utp_outcome=cancelled_after_winner utp_terminal_ms=40",
            metric.toLogLine()
        )

        var invoked = false
        P2pRuntimeMetricsReporter {
            invoked = true
            error("diagnostics sink failed")
        }.reportSafely(metric)
        assertTrue(invoked)
    }


    private suspend fun awaitMetric(metrics: List<P2pRuntimeMetric>) {
        withTimeout(1_000L) {
            while (metrics.isEmpty()) yield()
        }
    }

    private class ScriptedTransport(
        reads: List<ByteArray>,
        private val writeFailure: Throwable? = null,
        private val onClose: () -> Unit = {}
    ) : AceLiveTcpTransport {
        private val pendingReads = ArrayDeque(reads.map(ByteArray::copyOf))
        private val closed = AtomicBoolean(false)

        override suspend fun read(buffer: ByteArray): Int {
            if (closed.get()) return -1
            val next = pendingReads.pollFirst() ?: return 0
            val count = minOf(buffer.size, next.size)
            next.copyInto(buffer, endIndex = count)
            if (count < next.size) {
                pendingReads.addFirst(next.copyOfRange(count, next.size))
            }
            return count
        }

        override suspend fun write(bytes: ByteArray) {
            check(!closed.get())
            writeFailure?.let { throw it }
        }

        override suspend fun close() {
            if (closed.compareAndSet(false, true)) {
                onClose()
            }
        }
    }
}
