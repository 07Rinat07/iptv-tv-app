package com.iptv.tv.core.p2p

import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveHandshakeFailureBackoffRegressionTest {
    private val swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
        (index + 1).toByte()
    }
    private val localPeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x20 + index).toByte()
    }

    @Test
    fun firstPostConnectPreHandshakeFailureStaysBlockedForStartupWindow() = runBlocking {
        val endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9051)
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 0L },
            backoffMillis = 5_000L,
            repeatedFailureBackoffMillis = 60_000L
        )
        val events = mutableListOf<AceLiveTcpPoolEvent>()
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
            transportFactory = AceLiveTcpTransportFactory { _, _ -> RemoteCloseTransport() },
            policy = AceLiveTcpConnectionPolicy(
                connectTimeoutMillis = 1_000,
                readTimeoutMillis = 1_000,
                handshakeTimeoutMillis = 1_000,
                writeTimeoutMillis = 1_000,
                readBufferBytes = 4 * 1024,
                maxConcurrentPeers = 1,
                maxReconnectAttempts = 0,
                maxPreHandshakeReconnectAttempts = 0,
                reconnectDelayMillis = 0
            ),
            clockMillis = { 0L },
            connectFailureMemory = memory,
            onEvent = events::add
        )

        pool.startPeer(
            peerId = 51,
            endpoint = endpoint,
            swarmKey = swarmKey,
            localPeerId = localPeerId
        )

        withTimeout(2_000L) {
            while (pool.activePeerIds().isNotEmpty()) delay(5L)
        }

        assertTrue(
            events.filterIsInstance<AceLiveTcpPoolEvent.Disconnected>().any { event ->
                event.reason == AceLiveTcpDisconnectReason.REMOTE_CLOSED && !event.retrying
            }
        )
        assertFalse(memory.isEligible(swarmKey, endpoint, nowMillis = 18_000L))
        assertTrue(memory.isEligible(swarmKey, endpoint, nowMillis = 60_000L))

        pool.close()
    }

    private class RemoteCloseTransport : AceLiveTcpTransport {
        private val reads = Channel<Int>(Channel.UNLIMITED).apply {
            check(trySend(-1).isSuccess)
        }

        override suspend fun read(buffer: ByteArray): Int = reads.receive()

        override suspend fun write(bytes: ByteArray) {
            if (bytes.isEmpty()) throw IOException("empty handshake")
        }

        override suspend fun close() = Unit
    }
}
