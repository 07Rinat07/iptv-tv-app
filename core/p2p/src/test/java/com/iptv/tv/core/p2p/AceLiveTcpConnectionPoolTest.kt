package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
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
        val pool = pool(
            factory = FakeTransportFactory(transport),
            events = events
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

        val dispatch = pool.scheduleAndDispatch(head = 12, nowMillis = 1)

        assertEquals(3, dispatch.scheduledFrames)
        assertEquals(3, dispatch.selectedFrames)
        assertEquals(3, dispatch.sentFrames)
        assertTrue(dispatch.failedPeerIds.isEmpty())
        awaitCondition { transport.writes.size >= 5 }
        assertArrayEquals(
            handshakeCodec.encode(swarmKey, localPeerId),
            transport.writes[0]
        )
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 2), transport.writes[1])
        transport.writes.drop(2).take(3).forEach { requestFrame ->
            assertEquals(6, requestFrame[4].toInt() and 0xff)
        }

        pool.stopPeer(7)
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

    private fun pool(
        factory: AceLiveTcpTransportFactory,
        events: CopyOnWriteArrayList<AceLiveTcpPoolEvent>,
        policy: AceLiveTcpConnectionPolicy = policy()
    ): AceLiveTcpConnectionPool = AceLiveTcpConnectionPool(
        scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        session = session(),
        transportFactory = factory,
        policy = policy,
        clockMillis = { 0L },
        onEvent = events::add
    )

    private fun policy(
        maxConcurrentPeers: Int = 4,
        maxReconnectAttempts: Int = 0,
        reconnectDelayMillis: Long = 0
    ) = AceLiveTcpConnectionPolicy(
        connectTimeoutMillis = 1_000,
        readTimeoutMillis = 1_000,
        readBufferBytes = 4 * 1024,
        maxConcurrentPeers = maxConcurrentPeers,
        maxReconnectAttempts = maxReconnectAttempts,
        reconnectDelayMillis = reconnectDelayMillis
    )

    private fun session() = AceLivePeerSessionCoordinator(
        geometry = AceLiveTransportGeometry(
            pieceLengthBytes = 10,
            chunkLengthBytes = 4,
            bitrate = 1
        ),
        initialNextNeededPiece = 10,
        maxInFlightPerPeer = 1
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
        initialReads: List<ReadAction>
    ) : AceLiveTcpTransport {
        private val reads = Channel<ReadAction>(Channel.UNLIMITED)
        val writes = CopyOnWriteArrayList<ByteArray>()

        @Volatile
        private var closed = false

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
