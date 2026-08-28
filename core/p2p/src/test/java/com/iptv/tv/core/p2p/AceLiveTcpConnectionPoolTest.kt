package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTcpConnectionPoolTest {
    private val handshakeCodec = AceLivePeerHandshakeCodec()
    private val swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
        (index + 1).toByte()
    }
    private val localPeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x40 + index).toByte()
    }
    private val remotePeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x60 + index).toByte()
    }

    @Test
    fun handshakeMetadataAndUnchokeRouteScheduledChunkRequests() = runBlocking {
        val metadata = frame(
            id = 99,
            payload = ascii("d9:max_piecei12e9:min_piecei10ee")
        )
        val transport = FakeTransport(
            listOf(
                ReadAction.Data(
                    handshakeCodec.encode(swarmKey, remotePeerId) + metadata + frame(id = 1)
                )
            )
        )
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val producerDiagnostics = CopyOnWriteArrayList<String>()
        val pool = pool(
            factory = FakeTransportFactory(transport),
            events = events,
            producerDiagnostics = producerDiagnostics
        )

        pool.startPeer(
            peerId = 7,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9000),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted } &&
                events.filterIsInstance<AceLiveTcpPoolEvent.Ingress>()
                    .any { it.result.metadataUpdates.isNotEmpty() }
        }

        val quality = pool.peerProductionSnapshot(nowMillis = 0L)
        assertEquals(1, quality.connectedPeers)
        assertEquals(1, quality.handshakedPeers)
        assertEquals(1, quality.windowUsefulPeers)
        assertEquals(1, quality.unchokedPeers)
        assertEquals(0, quality.producingPeers)

        val dispatch = pool.scheduleAndDispatch(head = 12, nowMillis = 1)

        assertEquals(3, dispatch.scheduledFrames)
        assertEquals(3, dispatch.selectedFrames)
        assertEquals(3, dispatch.sentFrames)
        assertTrue(dispatch.failedPeerIds.isEmpty())
        assertTrue(
            producerDiagnostics.any { message ->
                message.contains("stage=sent") && message.contains("peer=7")
            }
        )
        awaitCondition { transport.writes.size >= 6 }
        assertArrayEquals(
            handshakeCodec.encode(swarmKey, localPeerId),
            transport.writes[0]
        )
        assertEquals(20, transport.writes[1][4].toInt() and 0xff)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2), transport.writes[2])
        transport.writes.drop(3).take(3).forEach { requestFrame ->
            assertEquals(6, requestFrame[4].toInt() and 0xff)
        }

        pool.stopPeer(7)
        val stopped = pool.peerQualitySnapshots(nowMillis = 2L).single { it.peerId == 7L }
        assertFalse(stopped.connected)
        assertFalse(stopped.handshaked)
        assertFalse(stopped.producing)
    }

    @Test
    fun wrongRemoteSwarmIsRejectedWithoutInterestedOrReconnect() = runBlocking {
        val wrongSwarm = swarmKey.copyOf().also { it[0] = 0x7f }
        val transport = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(wrongSwarm, remotePeerId)))
        )
        val factory = FakeTransportFactory(transport)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(factory = factory, events = events)

        pool.startPeer(
            peerId = 8,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9001),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeRejected }
        }
        awaitCondition { pool.activePeerIds().isEmpty() }

        val rejected = events.filterIsInstance<AceLiveTcpPoolEvent.HandshakeRejected>().single()
        assertEquals(AceLivePeerHandshakeRejectReason.SWARM_KEY_MISMATCH, rejected.reason)
        assertEquals(1, factory.connectCount)
        assertEquals(1, transport.writes.size)
        assertFalse(events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted })
    }

    @Test
    fun remoteCloseReconnectsWithinBoundAndReleasesConnectionState() = runBlocking {
        val first = FakeTransport(
            listOf(
                ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)),
                ReadAction.Eof
            )
        )
        val second = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val factory = FakeTransportFactory(first, second)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(maxReconnectAttempts = 1, reconnectDelayMillis = 0)
        )

        pool.startPeer(
            peerId = 9,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9002),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.filterIsInstance<AceLiveTcpPoolEvent.HandshakeAccepted>().size >= 2
        }

        assertEquals(2, factory.connectCount)
        assertTrue(
            events.filterIsInstance<AceLiveTcpPoolEvent.Disconnected>().any {
                it.reason == AceLiveTcpDisconnectReason.REMOTE_CLOSED && it.retrying
            }
        )

        pool.stopPeer(9)
    }

    @Test
    fun readTimeoutDoesNotDisconnectReachablePeer() = runBlocking {
        val transport = FakeTransport(
            listOf(
                ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)),
                ReadAction.Timeout
            )
        )
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(factory = FakeTransportFactory(transport), events = events)

        pool.startPeer(
            peerId = 10,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9003),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted }
        }
        delay(25)

        assertFalse(events.any { it is AceLiveTcpPoolEvent.Disconnected })
        assertEquals(setOf(10L), pool.activePeerIds())

        pool.stopPeer(10)
    }

    @Test
    fun peerCountIsBoundedBeforeOpeningAnotherTransport() = runBlocking {
        val first = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val factory = FakeTransportFactory(first)
        val pool = pool(
            factory = factory,
            events = CopyOnWriteArrayList(),
            policy = policy(maxConcurrentPeers = 1)
        )

        pool.startPeer(
            peerId = 11,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9004),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        val error = runCatching {
            pool.startPeer(
                peerId = 12,
                endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9005),
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals(setOf(11L), pool.activePeerIds())

        pool.stopPeer(11)
    }

    @Test
    fun silentPreHandshakePeerTimesOutAndReleasesPoolSlot() = runBlocking {
        val silent = FakeTransport(listOf(ReadAction.Timeout))
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(
            factory = FakeTransportFactory(silent),
            events = events,
            policy = policy(
                maxConcurrentPeers = 1,
                handshakeTimeoutMillis = 100
            )
        )

        pool.startPeer(
            peerId = 13,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9006),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition {
            events.filterIsInstance<AceLiveTcpPoolEvent.Disconnected>().any {
                it.peerId == 13L && it.reason == AceLiveTcpDisconnectReason.HANDSHAKE_TIMEOUT
            }
        }
        awaitCondition { pool.activePeerIds().isEmpty() }

        assertFalse(events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted })
    }

    @Test
    fun stalledPeerWriteDoesNotBlockHealthyPeerDispatch() = runBlocking {
        val peerPayload = handshakeCodec.encode(swarmKey, remotePeerId) +
            frame(id = 99, payload = ascii("d9:max_piecei11e9:min_piecei10ee")) +
            frame(id = 1)
        val stalled = FakeTransport(
            initialReads = listOf(ReadAction.Data(peerPayload)),
            blockWritesAfterCount = 3
        )
        val healthy = FakeTransport(listOf(ReadAction.Data(peerPayload)))
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val producerDiagnostics = CopyOnWriteArrayList<String>()
        val pool = pool(
            factory = FakeTransportFactory(stalled, healthy),
            events = events,
            policy = policy(writeTimeoutMillis = 100),
            producerDiagnostics = producerDiagnostics
        )

        pool.startPeer(
            peerId = 20,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9010),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        pool.startPeer(
            peerId = 21,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9011),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.filterIsInstance<AceLiveTcpPoolEvent.HandshakeAccepted>()
                .map { it.peerId }.toSet().containsAll(setOf(20L, 21L))
        }
        awaitCondition {
            events.filterIsInstance<AceLiveTcpPoolEvent.Ingress>()
                .filter { it.result.metadataUpdates.isNotEmpty() }
                .map { it.peerId }.toSet().containsAll(setOf(20L, 21L))
        }

        val dispatch = withTimeout(1_000) {
            pool.scheduleAndDispatch(head = 11, nowMillis = 1)
        }

        assertTrue(dispatch.failedPeerIds.isNotEmpty())
        assertTrue(dispatch.sentFrames >= 3)
        assertTrue(healthy.writes.drop(3).any { it.size >= 5 && (it[4].toInt() and 0xff) == 6 })
        assertTrue(producerDiagnostics.any { it.contains("stage=sent") && it.contains("peer=21") })
        assertFalse(producerDiagnostics.any { it.contains("stage=sent") && it.contains("peer=20") })

        pool.close()
    }

    @Test
    fun closedPoolRejectsLatePeerStartsWithoutOpeningTransport() = runBlocking {
        val transport = FakeTransport(emptyList())
        val factory = FakeTransportFactory(transport)
        val pool = pool(
            factory = factory,
            events = CopyOnWriteArrayList(),
            policy = policy(maxConcurrentPeers = 1)
        )

        pool.close()

        val error = runCatching {
            pool.startPeer(
                peerId = 29,
                endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9019),
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals(0, factory.connectCount)
        assertTrue(pool.activePeerIds().isEmpty())
    }

    @Test
    fun inboundPeerIsAdoptedWithoutOpeningOutboundTransport() = runBlocking {
        val metadata = frame(
            id = 99,
            payload = ascii("d9:max_piecei12e9:min_piecei10ee")
        )
        val inbound = FakeTransport(
            listOf(
                ReadAction.Data(
                    handshakeCodec.encode(swarmKey, remotePeerId) + metadata + frame(id = 1)
                )
            )
        )
        val factory = FakeTransportFactory()
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(factory = factory, events = events)

        assertTrue(
            pool.startInboundPeer(
                peerId = 31,
                endpoint = AceLiveTcpPeerEndpoint("198.51.100.20", 45000),
                transport = inbound,
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted && it.peerId == 31L }
        }

        assertEquals(0, factory.connectCount)
        assertTrue(pool.activePeerIds().contains(31L))
        assertTrue(inbound.writes.size >= 3)
        assertArrayEquals(handshakeCodec.encode(swarmKey, localPeerId), inbound.writes[0])

        pool.stopPeer(31)
    }

    @Test
    fun wrongInboundSwarmIsRejectedBeforeLocalHandshakeIsRevealed() = runBlocking {
        val wrongSwarm = swarmKey.copyOf().also { it[0] = 0x7f }
        val inbound = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(wrongSwarm, remotePeerId)))
        )
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(factory = FakeTransportFactory(), events = events)

        assertTrue(
            pool.startInboundPeer(
                peerId = 32,
                endpoint = AceLiveTcpPeerEndpoint("198.51.100.21", 45001),
                transport = inbound,
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeRejected && it.peerId == 32L }
        }
        awaitCondition { pool.activePeerIds().isEmpty() }

        assertTrue(inbound.writes.isEmpty())
        assertFalse(
            events.any { it is AceLiveTcpPoolEvent.TransportConnected && it.peerId == 32L }
        )
    }

    @Test
    fun inboundCapacityIsBoundedWithoutConsumingReservedOutboundSlot() = runBlocking {
        val firstInbound = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val rejectedInbound = FakeTransport(emptyList())
        val outbound = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val secondOutbound = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val factory = FakeTransportFactory(outbound, secondOutbound)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(maxConcurrentPeers = 2, maxConcurrentInboundPeers = 1)
        )

        assertTrue(
            pool.startInboundPeer(
                peerId = 33,
                endpoint = AceLiveTcpPeerEndpoint("198.51.100.22", 45002),
                transport = firstInbound,
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted && it.peerId == 33L }
        }

        assertFalse(
            pool.startInboundPeer(
                peerId = 34,
                endpoint = AceLiveTcpPeerEndpoint("198.51.100.23", 45003),
                transport = rejectedInbound,
                swarmKey = swarmKey,
                localPeerId = localPeerId
            )
        )
        assertTrue(rejectedInbound.isClosed)

        pool.startPeer(
            peerId = 35,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9025),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted && it.peerId == 35L }
        }
        pool.startPeer(
            peerId = 36,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9026),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted && it.peerId == 36L }
        }

        assertEquals(2, factory.connectCount)
        assertEquals(setOf(35L, 36L), pool.outboundPeerIds())

        pool.stopPeer(33)
        pool.stopPeer(35)
        pool.stopPeer(36)
    }

    @Test
    fun immediateStopReleasesRegisteredPeerSlot() = runBlocking {
        val transport = FakeTransport(emptyList())
        val pool = pool(
            factory = FakeTransportFactory(transport),
            events = CopyOnWriteArrayList(),
            policy = policy(maxConcurrentPeers = 1)
        )

        pool.startPeer(
            peerId = 30,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9020),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        pool.stopPeer(30)

        assertTrue(pool.activePeerIds().isEmpty())
    }

    private fun pool(
        factory: AceLiveTcpTransportFactory,
        events: CopyOnWriteArrayList<AceLiveTcpPoolEvent>,
        policy: AceLiveTcpConnectionPolicy = policy(),
        producerDiagnostics: CopyOnWriteArrayList<String>? = null
    ): AceLiveTcpConnectionPool = AceLiveTcpConnectionPool(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        session = session(producerDiagnostics),
        transportFactory = factory,
        policy = policy,
        clockMillis = { 0L },
        onEvent = events::add
    )

    private fun policy(
        maxConcurrentPeers: Int = 4,
        maxConcurrentInboundPeers: Int = 4,
        maxReconnectAttempts: Int = 0,
        reconnectDelayMillis: Long = 0,
        handshakeTimeoutMillis: Int = 1_000,
        writeTimeoutMillis: Int = 1_000
    ) = AceLiveTcpConnectionPolicy(
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        handshakeTimeoutMillis = handshakeTimeoutMillis,
        writeTimeoutMillis = writeTimeoutMillis,
        readBufferBytes = 4 * 1024,
        maxConcurrentPeers = maxConcurrentPeers,
        maxConcurrentInboundPeers = maxConcurrentInboundPeers,
        maxReconnectAttempts = maxReconnectAttempts,
        reconnectDelayMillis = reconnectDelayMillis
    )

    private fun session(
        producerDiagnostics: CopyOnWriteArrayList<String>? = null
    ) = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxInFlightPerPeer = 1,
        producerBoundaryDiagnostics = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { status, message -> producerDiagnostics?.add("$status $message") }
        )
    )

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) {
                delay(5)
            }
        }
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

    private sealed interface ReadAction {
        data class Data(val bytes: ByteArray) : ReadAction
        data object Timeout : ReadAction
        data object Eof : ReadAction
    }

    private class FakeTransport(
        initialReads: List<ReadAction>,
        private val blockWritesAfterCount: Int? = null
    ) : AceLiveTcpTransport {
        private val reads = Channel<ReadAction>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<ByteArray>()

        @Volatile
        private var closed = false

        val isClosed: Boolean
            get() = closed

        init {
            initialReads.forEach { action ->
                check(reads.trySend(action).isSuccess)
            }
        }

        override suspend fun read(buffer: ByteArray): Int {
            val action = reads.receive()
            return when (action) {
                is ReadAction.Data -> {
                    if (closed) return -1
                    require(action.bytes.size <= buffer.size) { "fake read does not fit buffer" }
                    action.bytes.copyInto(buffer)
                    action.bytes.size
                }

                ReadAction.Timeout -> if (closed) -1 else 0
                ReadAction.Eof -> -1
            }
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("fake transport is closed")
            if (blockWritesAfterCount != null && writes.size >= blockWritesAfterCount) {
                awaitCancellation()
            }
            if (closed) throw IOException("fake transport is closed")
            writes += bytes.copyOf()
        }

        override suspend fun close() {
            if (!closed) {
                closed = true
                reads.trySend(ReadAction.Eof)
            }
        }
    }

    private class FakeTransportFactory(
        vararg transports: FakeTransport
    ) : AceLiveTcpTransportFactory {
        private val queue = ArrayDeque(transports.toList())

        @Volatile
        var connectCount: Int = 0
            private set

        override suspend fun connect(
            endpoint: AceLiveTcpPeerEndpoint,
            policy: AceLiveTcpConnectionPolicy
        ): AceLiveTcpTransport {
            connectCount += 1
            return synchronized(queue) {
                if (queue.isEmpty()) throw IOException("no fake transport")
                queue.removeFirst()
            }
        }
    }
}
