package com.iptv.tv.core.p2p

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveStartupCandidateRacingTest {
    private val swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { index ->
        (index + 1).toByte()
    }
    private val localPeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { index ->
        (0x30 + index).toByte()
    }

    @Test
    fun `second startup candidate races after stagger while first connect is still hanging`() {
        runBlocking {
            val firstConnectStarted = CompletableDeferred<Unit>()
            val secondConnectStarted = CompletableDeferred<Unit>()
            val releaseFirstConnect = CompletableDeferred<Unit>()
            val factory = object : AceLiveTcpTransportFactory {
                override suspend fun connect(
                    endpoint: AceLiveTcpPeerEndpoint,
                    policy: AceLiveTcpConnectionPolicy
                ): AceLiveTcpTransport = when (endpoint.port) {
                    9_100 -> {
                        firstConnectStarted.complete(Unit)
                        releaseFirstConnect.await()
                        HangingTransport()
                    }

                    9_101 -> {
                        secondConnectStarted.complete(Unit)
                        HangingTransport()
                    }

                    else -> error("unexpected endpoint $endpoint")
                }
            }
            val pool = pool(
                factory = factory,
                startupCandidateStaggerMillis = 200L
            )

            try {
                pool.startPeer(
                    peerId = 1L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_100),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )
                withTimeout(500L) { firstConnectStarted.await() }

                pool.startPeer(
                    peerId = 2L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_101),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )

                delay(50L)
                assertFalse(secondConnectStarted.isCompleted)
                withTimeout(1_000L) { secondConnectStarted.await() }
                assertFalse(releaseFirstConnect.isCompleted)
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun `first connected transport releases an already waiting startup candidate`() {
        runBlocking {
            val firstConnectStarted = CompletableDeferred<Unit>()
            val secondConnectStarted = CompletableDeferred<Unit>()
            val releaseFirstConnect = CompletableDeferred<Unit>()
            val factory = object : AceLiveTcpTransportFactory {
                override suspend fun connect(
                    endpoint: AceLiveTcpPeerEndpoint,
                    policy: AceLiveTcpConnectionPolicy
                ): AceLiveTcpTransport = when (endpoint.port) {
                    9_150 -> {
                        firstConnectStarted.complete(Unit)
                        releaseFirstConnect.await()
                        HangingTransport()
                    }

                    9_151 -> {
                        secondConnectStarted.complete(Unit)
                        HangingTransport()
                    }

                    else -> error("unexpected endpoint $endpoint")
                }
            }
            val pool = pool(
                factory = factory,
                startupCandidateStaggerMillis = 5_000L
            )

            try {
                pool.startPeer(
                    peerId = 5L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_150),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )
                withTimeout(500L) { firstConnectStarted.await() }

                pool.startPeer(
                    peerId = 6L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_151),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )

                delay(50L)
                assertFalse(secondConnectStarted.isCompleted)
                releaseFirstConnect.complete(Unit)

                withTimeout(500L) { secondConnectStarted.await() }
                assertTrue(secondConnectStarted.isCompleted)
            } finally {
                pool.close()
            }
        }
    }

    @Test
    fun `later refill candidate starts immediately after any transport has connected`() {
        runBlocking {
            val firstConnectStarted = CompletableDeferred<Unit>()
            val secondConnectStarted = CompletableDeferred<Unit>()
            val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
            val factory = object : AceLiveTcpTransportFactory {
                override suspend fun connect(
                    endpoint: AceLiveTcpPeerEndpoint,
                    policy: AceLiveTcpConnectionPolicy
                ): AceLiveTcpTransport = when (endpoint.port) {
                    9_200 -> {
                        firstConnectStarted.complete(Unit)
                        HangingTransport()
                    }

                    9_201 -> {
                        secondConnectStarted.complete(Unit)
                        HangingTransport()
                    }

                    else -> error("unexpected endpoint $endpoint")
                }
            }
            val pool = pool(
                factory = factory,
                startupCandidateStaggerMillis = 5_000L,
                events = events
            )

            try {
                pool.startPeer(
                    peerId = 10L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_200),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )
                withTimeout(500L) { firstConnectStarted.await() }
                withTimeout(500L) {
                    while (
                        events.none { event ->
                            event is AceLiveTcpPoolEvent.TransportConnected && event.peerId == 10L
                        }
                    ) {
                        delay(5L)
                    }
                }

                pool.startPeer(
                    peerId = 11L,
                    endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9_201),
                    swarmKey = swarmKey,
                    localPeerId = localPeerId
                )

                withTimeout(500L) { secondConnectStarted.await() }
                assertTrue(secondConnectStarted.isCompleted)
            } finally {
                pool.close()
            }
        }
    }

    private fun pool(
        factory: AceLiveTcpTransportFactory,
        startupCandidateStaggerMillis: Long,
        events: CopyOnWriteArrayList<AceLiveTcpPoolEvent> = CopyOnWriteArrayList()
    ): AceLiveTcpConnectionPool = AceLiveTcpConnectionPool(
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
        policy = AceLiveTcpConnectionPolicy(
            connectTimeoutMillis = 1_000,
            readTimeoutMillis = 1_000,
            handshakeTimeoutMillis = 1_000,
            writeTimeoutMillis = 1_000,
            readBufferBytes = 4 * 1_024,
            maxConcurrentPeers = 4,
            maxReconnectAttempts = 0,
            reconnectDelayMillis = 0L
        ),
        startupCandidateStaggerMillis = startupCandidateStaggerMillis,
        maxStaggeredStartupCandidates = 4,
        onEvent = events::add
    )

    private class HangingTransport : AceLiveTcpTransport {
        override suspend fun read(buffer: ByteArray): Int = awaitCancellation()

        override suspend fun write(bytes: ByteArray) = Unit

        override suspend fun close() = Unit
    }
}
