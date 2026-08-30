package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTcpConnectionPoolProducingBoundaryTest {
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
    fun `pool marks only verified requestable peer producing after accepted media evidence`() = runBlocking {
        val transport = FakeTransport(
            handshakeCodec.encode(swarmKey, remotePeerId) +
                frame(id = 99, payload = ascii("d9:max_piecei12e9:min_piecei10ee")) +
                frame(id = 1)
        )
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val pool = AceLiveTcpConnectionPool(
            scope = CoroutineScope(Dispatchers.Default),
            session = AceLivePeerSessionCoordinator(
                geometry = AceLiveTransportGeometry(
                    pieceLengthBytes = 10,
                    chunkLengthBytes = 4,
                    bitrate = 1
                ),
                initialNextNeededPiece = 10,
                maxInFlightPerPeer = 1
            ),
            transportFactory = FakeTransportFactory(transport),
            policy = AceLiveTcpConnectionPolicy(
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                handshakeTimeoutMillis = 1_000,
                writeTimeoutMillis = 1_000,
                readBufferBytes = 4 * 1024,
                maxConcurrentPeers = 1,
                maxConcurrentInboundPeers = 1,
                maxReconnectAttempts = 0,
                reconnectDelayMillis = 0
            ),
            clockMillis = { CONNECTED_AT_MILLIS },
            onEvent = events::add
        )

        pool.recordMediaProduced(
            peerId = UNKNOWN_PEER_ID,
            mediaBytes = MEDIA_BYTES,
            nowMillis = MEDIA_AT_MILLIS
        )
        assertTrue(pool.peerQualitySnapshots(MEDIA_AT_MILLIS).isEmpty())

        pool.startPeer(
            peerId = PEER_ID,
            endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9000),
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )
        awaitCondition {
            events.any { it is AceLiveTcpPoolEvent.HandshakeAccepted && it.peerId == PEER_ID } &&
                events.filterIsInstance<AceLiveTcpPoolEvent.Ingress>()
                    .any { event ->
                        event.peerId == PEER_ID && event.result.metadataUpdates.isNotEmpty()
                    }
        }

        val beforeMedia = pool.peerProductionSnapshot(MEDIA_AT_MILLIS)
        assertEquals(1, beforeMedia.connectedPeers)
        assertEquals(1, beforeMedia.handshakedPeers)
        assertEquals(1, beforeMedia.windowUsefulPeers)
        assertEquals(1, beforeMedia.unchokedPeers)
        assertEquals(0, beforeMedia.producingPeers)

        pool.recordMediaProduced(
            peerId = PEER_ID,
            mediaBytes = MEDIA_BYTES,
            nowMillis = MEDIA_AT_MILLIS
        )

        val afterMedia = pool.peerProductionSnapshot(MEDIA_AT_MILLIS)
        assertEquals(1, afterMedia.connectedPeers)
        assertEquals(1, afterMedia.handshakedPeers)
        assertEquals(1, afterMedia.windowUsefulPeers)
        assertEquals(1, afterMedia.unchokedPeers)
        assertEquals(1, afterMedia.producingPeers)
        assertEquals(0L, afterMedia.freshestMediaAgeMillis)

        val producingPeer = pool.peerQualitySnapshots(MEDIA_AT_MILLIS).single()
        assertEquals(PEER_ID, producingPeer.peerId)
        assertTrue(producingPeer.connected)
        assertTrue(producingPeer.handshaked)
        assertTrue(producingPeer.windowUseful)
        assertTrue(producingPeer.unchoked)
        assertTrue(producingPeer.producing)
        assertEquals(MEDIA_BYTES, producingPeer.totalMediaBytes)
        assertEquals(0L, producingPeer.mediaAgeMillis)

        pool.stopPeer(PEER_ID)
        awaitCondition { pool.activePeerIds().isEmpty() }
        pool.recordMediaProduced(
            peerId = PEER_ID,
            mediaBytes = MEDIA_BYTES,
            nowMillis = MEDIA_AT_MILLIS + 100
        )

        val stoppedPeer = pool.peerQualitySnapshots(MEDIA_AT_MILLIS + 100).single()
        assertFalse(stoppedPeer.connected)
        assertFalse(stoppedPeer.handshaked)
        assertFalse(stoppedPeer.windowUseful)
        assertFalse(stoppedPeer.unchoked)
        assertFalse(stoppedPeer.producing)
        assertEquals(MEDIA_BYTES * 2, stoppedPeer.totalMediaBytes)
        assertEquals(0, pool.peerProductionSnapshot(MEDIA_AT_MILLIS + 100).producingPeers)

        pool.close()
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(2_000) {
            while (!condition()) delay(5)
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

    private class FakeTransport(initialRead: ByteArray) : AceLiveTcpTransport {
        private val reads = Channel<ByteArray?>(Channel.UNLIMITED)

        @Volatile
        private var closed = false

        init {
            check(reads.trySend(initialRead).isSuccess)
        }

        override suspend fun read(buffer: ByteArray): Int {
            val bytes = reads.receive() ?: return -1
            if (closed) return -1
            require(bytes.size <= buffer.size) { "fake read does not fit buffer" }
            bytes.copyInto(buffer)
            return bytes.size
        }

        override suspend fun write(bytes: ByteArray) {
            if (closed) throw IOException("fake transport is closed")
        }

        override suspend fun close() {
            if (closed) return
            closed = true
            reads.trySend(null)
        }
    }

    private class FakeTransportFactory(
        vararg transports: FakeTransport
    ) : AceLiveTcpTransportFactory {
        private val queue = ArrayDeque(transports.toList())

        override suspend fun connect(
            endpoint: AceLiveTcpPeerEndpoint,
            policy: AceLiveTcpConnectionPolicy
        ): AceLiveTcpTransport = synchronized(queue) {
            if (queue.isEmpty()) throw IOException("no fake transport")
            queue.removeFirst()
        }
    }

    private companion object {
        const val PEER_ID = 7L
        const val UNKNOWN_PEER_ID = 99L
        const val CONNECTED_AT_MILLIS = 100L
        const val MEDIA_AT_MILLIS = 1_000L
        const val MEDIA_BYTES = 940L
    }
}
