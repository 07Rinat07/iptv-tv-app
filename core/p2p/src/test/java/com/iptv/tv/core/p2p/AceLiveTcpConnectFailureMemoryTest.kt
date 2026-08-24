package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveTcpConnectFailureMemoryTest {
    @Test
    fun `failure backoff is scoped by swarm and endpoint and expires at existing bound`() {
        var now = 1_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { now },
            backoffMillis = 5_000L
        )
        val failed = AceLiveTcpPeerEndpoint("8.8.8.8", 8621)
        val alternate = AceLiveTcpPeerEndpoint("1.1.1.1", 8621)
        val firstSwarm = swarm(1)
        val secondSwarm = swarm(2)

        memory.recordFinalPreHandshakeFailure(firstSwarm.toByteArray(), failed)

        assertFalse(memory.isEligible(firstSwarm.toByteArray(), failed))
        assertTrue(memory.isEligible(firstSwarm.toByteArray(), alternate))
        assertTrue(memory.isEligible(secondSwarm.toByteArray(), failed))

        now = 5_999L
        assertFalse(memory.isEligible(firstSwarm.toByteArray(), failed))
        now = 6_000L
        assertTrue(memory.isEligible(firstSwarm.toByteArray(), failed))
    }

    @Test
    fun `successful tcp connection clears previous negative memory`() {
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 5_000L
        )
        val endpoint = AceLiveTcpPeerEndpoint("8.8.4.4", 8621)
        val swarm = swarm(3)

        memory.recordFinalPreHandshakeFailure(swarm.toByteArray(), endpoint)
        assertFalse(memory.isEligible(swarm.toByteArray(), endpoint))

        memory.recordConnected(swarm.toByteArray(), endpoint)

        assertTrue(memory.isEligible(swarm.toByteArray(), endpoint))
    }

    @Test
    fun `final pre-handshake tcp failure is retained by pool`() = runBlocking {
        var now = 10_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { now },
            backoffMillis = 5_000L
        )
        val events = CopyOnWriteArrayList<AceLiveTcpPoolEvent>()
        val endpoint = AceLiveTcpPeerEndpoint("8.8.8.8", 9000)
        val swarm = swarm(4)
        val pool = AceLiveTcpConnectionPool(
            scope = CoroutineScope(Dispatchers.Default),
            session = AceLivePeerSessionCoordinator(
                geometry = AceLiveTransportGeometry(
                    pieceLengthBytes = 10,
                    chunkLengthBytes = 4,
                    bitrate = 1
                ),
                initialNextNeededPiece = 0,
                maxInFlightPerPeer = 1
            ),
            transportFactory = AceLiveTcpTransportFactory { _, _ ->
                throw IOException("simulated connect failure")
            },
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
            clockMillis = { now },
            connectFailureMemory = memory,
            onEvent = events::add
        )

        pool.startPeer(
            peerId = 7,
            endpoint = endpoint,
            swarmKey = swarm.toByteArray(),
            localPeerId = ByteArray(AceLivePeerHandshakeCodec.PEER_ID_BYTES) { 7 }
        )
        withTimeout(1_000L) {
            while (events.none { event ->
                    event is AceLiveTcpPoolEvent.ConnectFailed && !event.retrying
                }) {
                delay(5L)
            }
        }

        assertFalse(memory.isEligible(swarm.toByteArray(), endpoint))
        now += 5_000L
        assertTrue(memory.isEligible(swarm.toByteArray(), endpoint))
        pool.close()
    }

    @Test
    fun `failed tracker fast path falls through to eligible dht alternative`() = runBlocking {
        val now = 20_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { now },
            backoffMillis = 5_000L
        )
        val swarm = swarm(5)
        val failedTrackerPeer = AceLiveTcpPeerEndpoint("8.8.8.8", 9100)
        val dhtAlternative = AceLiveTcpPeerEndpoint("1.1.1.1", 9101)
        memory.recordFinalPreHandshakeFailure(swarm.toByteArray(), failedTrackerPeer)
        var dhtCalled = false

        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                dhtCalled = true
                AceLiveDhtDiscoveryResult(
                    peers = listOf(dhtAlternative, failedTrackerPeer),
                    queriesSent = 3,
                    failedQueries = 0,
                    rejectedEndpoints = 0
                )
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(
                    peers = listOf(failedTrackerPeer),
                    attemptedTrackers = 1,
                    failedTrackers = 0,
                    rejectedTrackers = 0
                )
            },
            policy = AceLivePeerDiscoveryOrchestrationPolicy(
                trackerFastPathMinPeers = 1
            ),
            dhtHeadroomAvailable = { true },
            connectFailureMemory = memory
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = AceLiveDhtDiscoveryRequest(
                    swarmKey = swarm,
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", 6881))
                ),
                trackerRequest = AceLiveUdpTrackerDiscoveryRequest(
                    swarmKey = swarm,
                    trackers = listOf("udp://tracker.test:6969"),
                    peerId = ByteArray(AceLiveUdpTrackerCodec.PEER_ID_BYTES) { 8 },
                    announcePort = 51413
                )
            )
        )

        assertTrue(dhtCalled)
        assertEquals(listOf(dhtAlternative), result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.tracker.status)
        assertEquals(1, result.tracker.returnedPeerCount)
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.dht.status)
        assertEquals(2, result.dht.returnedPeerCount)
    }

    @Test
    fun `startup refill strength uses eligible dht peers while preserving raw count`() = runBlocking {
        val now = 30_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { now },
            backoffMillis = 5_000L
        )
        val swarm = swarm(6)
        val peers = listOf(
            AceLiveTcpPeerEndpoint("1.1.1.1", 9201),
            AceLiveTcpPeerEndpoint("8.8.8.8", 9202),
            AceLiveTcpPeerEndpoint("9.9.9.9", 9203),
            AceLiveTcpPeerEndpoint("4.2.2.2", 9204),
            AceLiveTcpPeerEndpoint("208.67.222.222", 9205)
        )
        peers.take(3).forEach { peer ->
            memory.recordFinalPreHandshakeFailure(swarm.toByteArray(), peer)
        }
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                AceLiveDhtDiscoveryResult(
                    peers = peers,
                    queriesSent = 6,
                    failedQueries = 1,
                    rejectedEndpoints = 0
                )
            },
            policy = AceLivePeerDiscoveryOrchestrationPolicy(
                preferTrackerFastPath = false
            ),
            dhtHeadroomAvailable = { true },
            connectFailureMemory = memory
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = AceLiveDhtDiscoveryRequest(
                    swarmKey = swarm,
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", 6881))
                )
            )
        )

        assertEquals(peers.drop(3), result.tcpEndpoints())
        assertEquals(5, result.dht.returnedPeerCount)
        assertEquals(
            AceLiveStartupDhtRefillPlan.PROBE_BATCHES_THEN_EXPAND,
            aceLiveStartupDhtRefillPlan(result)
        )
    }

    private fun swarm(fill: Int): AceLiveSwarmKey =
        AceLiveSwarmKey.fromBytes(ByteArray(AceLiveSwarmKey.BYTES) { fill.toByte() })
}
