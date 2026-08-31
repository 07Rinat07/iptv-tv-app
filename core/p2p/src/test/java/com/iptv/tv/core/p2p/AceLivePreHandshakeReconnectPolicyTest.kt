package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePreHandshakeReconnectPolicyTest {
    private val handshakeCodec = AceLivePeerHandshakeCodec()
    private val swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
        (index + 1).toByte()
    }
    private val localPeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x20 + index).toByte()
    }
    private val remotePeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x60 + index).toByte()
    }

    @Test
    fun preHandshakeRemoteCloseDoesNotConsumeEstablishedReconnectBudget() = runBlocking {
        val unqualified = FakeTransport(listOf(ReadAction.Eof))
        val unusedAlternative = FakeTransport(
            listOf(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        )
        val factory = FakeTransportFactory(unqualified, unusedAlternative)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 0L },
            backoffMillis = 5_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9041)
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(
                maxReconnectAttempts = 2,
                maxPreHandshakeReconnectAttempts = 0
            ),
            connectFailureMemory = memory
        )

        pool.startPeer(
            peerId = 41,
            endpoint = endpoint,
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition { pool.activePeerIds().isEmpty() }

        assertEquals(1, factory.connectCount)
        assertFalse(events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted })
        assertTrue(
            events.filterIsInstance<AceLiveTcpPoolEvent.Disconnected>().any {
                it.peerId == 41L &&
                    it.reason == AceLiveTcpDisconnectReason.REMOTE_CLOSED &&
                    !it.retrying
            }
        )
        assertFalse(memory.isEligible(swarmKey, endpoint, nowMillis = 0L))
    }

    @Test
    fun outboundFailureMemoryClearsOnlyAfterAcceptedHandshake() = runBlocking {
        val transport = FakeTransport(emptyList())
        val factory = FakeTransportFactory(transport)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 0L },
            backoffMillis = 5_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9044)
        memory.recordFinalPreHandshakeFailure(
            swarmKey = swarmKey,
            endpoint = endpoint,
            nowMillis = 0L
        )
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(
                maxReconnectAttempts = 0,
                maxPreHandshakeReconnectAttempts = 0
            ),
            connectFailureMemory = memory
        )

        pool.startPeer(
            peerId = 44,
            endpoint = endpoint,
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition {
            events.any { event -> event is AceLiveTcpPoolEvent.TransportConnected }
        }
        assertFalse(memory.isEligible(swarmKey, endpoint, nowMillis = 0L))

        transport.enqueue(ReadAction.Data(handshakeCodec.encode(swarmKey, remotePeerId)))
        awaitCondition {
            events.any { event -> event is AceLiveTcpPoolEvent.HandshakeAccepted }
        }
        assertTrue(memory.isEligible(swarmKey, endpoint, nowMillis = 0L))

        pool.stopPeer(44)
    }

    @Test
    fun explicitStopBeforeHandshakeDoesNotCreateCrossRuntimeFailure() = runBlocking {
        val transport = FakeTransport(emptyList(), closeDelayMillis = 50L)
        val factory = FakeTransportFactory(transport)
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 0L },
            backoffMillis = 5_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9045)
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(
                maxReconnectAttempts = 0,
                maxPreHandshakeReconnectAttempts = 0
            ),
            connectFailureMemory = memory
        )

        pool.startPeer(
            peerId = 45,
            endpoint = endpoint,
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition {
            events.any { event -> event is AceLiveTcpPoolEvent.TransportConnected }
        }
        pool.stopPeer(45)

        assertTrue(memory.isEligible(swarmKey, endpoint, nowMillis = 0L))
    }

    @Test
    fun establishedPeerKeepsBoundedReconnectBudget() = runBlocking {
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
            policy = policy(
                maxReconnectAttempts = 1,
                maxPreHandshakeReconnectAttempts = 0
            )
        )

        pool.startPeer(
            peerId = 42,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9042),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition {
            events.filterIsInstance<AceLiveTcpPoolEvent.HandshakeAccepted>().size >= 2
        }

        assertEquals(2, factory.connectCount)
        assertTrue(
            events.filterIsInstance<AceLiveTcpPoolEvent.Disconnected>().any {
                it.peerId == 42L &&
                    it.reason == AceLiveTcpDisconnectReason.REMOTE_CLOSED &&
                    it.retrying
            }
        )

        pool.stopPeer(42)
    }

    @Test
    fun preHandshakeConnectFailureDoesNotRetrySameEndpointByDefault() = runBlocking {
        val factory = FailingTransportFactory()
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = pool(
            factory = factory,
            events = events,
            policy = policy(
                maxReconnectAttempts = 2,
                maxPreHandshakeReconnectAttempts = 0
            )
        )

        pool.startPeer(
            peerId = 43,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9043),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        awaitCondition { pool.activePeerIds().isEmpty() }

        assertEquals(1, factory.connectCount)
        val failure = events.filterIsInstance<AceLiveTcpPoolEvent.ConnectFailed>().single()
        assertFalse(failure.retrying)
    }

    private fun pool(
        factory: AceLiveTcpTransportFactory,
        events: CopyOnWriteArrayList<AceLiveTcpPoolEvent>,
        policy: AceLiveTcpConnectionPolicy,
        connectFailureMemory: AceLiveTcpConnectFailureMemory = AceLiveTcpConnectFailureMemory()
    ) = AceLiveTcpConnectionPool(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        session = AceLivePeerSessionCoordinator(
            geometry = AceLiveTransportGeometry(
                pieceLengthBytes = 10,
                chunkLengthBytes = 4,
                bitrate = 1
            ),
            initialNextNeededPiece = 10,
            maxInFlightPerPeer = 1
        ),
        transportFactory = factory,
        policy = policy,
        clockMillis = { 0L },
        connectFailureMemory = connectFailureMemory,
        onEvent = events::add
    )

    private fun policy(
        maxReconnectAttempts: Int,
        maxPreHandshakeReconnectAttempts: Int
    ) = AceLiveTcpConnectionPolicy(
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        handshakeTimeoutMillis = 1_000,
        writeTimeoutMillis = 1_000,
        readBufferBytes = 4 * 1024,
        maxConcurrentPeers = 4,
        maxReconnectAttempts = maxReconnectAttempts,
        maxPreHandshakeReconnectAttempts = maxPreHandshakeReconnectAttempts,
        reconnectDelayMillis = 0
    )

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
        }
    }

    private sealed interface ReadAction {
        data class Data(val bytes: ByteArray) : ReadAction
        data object Eof : ReadAction
    }

    private class FakeTransport(
        initialReads: List<ReadAction>,
        private val closeDelayMillis: Long = 0L
    ) : AceLiveTcpTransport {
        private val reads = Channel<ReadAction>(Channel.UNLIMITED)

        @Volatile
        private var closed = false

        init {
            initialReads.forEach { action -> check(reads.trySend(action).isSuccess) }
        }

        fun enqueue(action: ReadAction) {
            check(reads.trySend(action).isSuccess)
        }

        override suspend fun read(buffer: ByteArray): Int {
            val action = reads.receive()
            return when (action) {
                is ReadAction.Data -> {
                    if (closed) return -1
                    require(action.bytes.size <= buffer.size)
                    action.bytes.copyInto(buffer)
                    action.bytes.size
                }
                ReadAction.Eof -> -1
            }
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("fake transport is closed")
        }

        override suspend fun close() {
            if (!closed) {
                closed = true
                reads.trySend(ReadAction.Eof)
                if (closeDelayMillis > 0L) delay(closeDelayMillis)
            }
        }
    }

    private class FakeTransportFactory(vararg transports: FakeTransport) : AceLiveTcpTransportFactory {
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

    private class FailingTransportFactory : AceLiveTcpTransportFactory {
        @Volatile
        var connectCount: Int = 0
            private set

        override suspend fun connect(
            endpoint: AceLiveTcpPeerEndpoint,
            policy: AceLiveTcpConnectionPolicy
        ): AceLiveTcpTransport {
            connectCount += 1
            throw IOException("connect failed")
        }
    }
}
