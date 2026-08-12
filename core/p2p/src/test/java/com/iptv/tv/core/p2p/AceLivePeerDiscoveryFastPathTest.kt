package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerDiscoveryFastPathTest {
    @Test
    fun `tracker fast path avoids dht when tracker already has a useful peer batch`() = runBlocking {
        val swarm = swarm(21)
        var dhtCalled = false
        val trackerPeers = listOf(
            AceLiveTcpPeerEndpoint("1.1.1.1", 8101),
            AceLiveTcpPeerEndpoint("8.8.8.8", 8102),
            AceLiveTcpPeerEndpoint("9.9.9.9", 8103),
            AceLiveTcpPeerEndpoint("4.2.2.2", 8104)
        )
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                dhtCalled = true
                AceLiveDhtDiscoveryResult(emptyList(), 0, 0, 0)
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(trackerPeers, 1, 0, 0)
            },
            dhtHeadroomAvailable = { true }
        )

        val result = orchestrator.discover(request(swarm))

        assertFalse(dhtCalled)
        assertEquals(trackerPeers, result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.NOT_REQUESTED, result.dht.status)
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.tracker.status)
    }

    @Test
    fun `weak tracker batch falls back to dht`() = runBlocking {
        val swarm = swarm(22)
        var dhtCalled = false
        val trackerPeer = AceLiveTcpPeerEndpoint("1.1.1.1", 8201)
        val dhtPeer = AceLiveTcpPeerEndpoint("8.8.8.8", 8202)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                dhtCalled = true
                AceLiveDhtDiscoveryResult(listOf(dhtPeer), 1, 0, 0)
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(listOf(trackerPeer), 1, 0, 0)
            },
            dhtHeadroomAvailable = { true }
        )

        val result = orchestrator.discover(request(swarm))

        assertTrue(dhtCalled)
        assertEquals(listOf(dhtPeer, trackerPeer), result.tcpEndpoints())
    }

    @Test
    fun `concurrent weak tracker startups serialize memory heavy dht walks`() = runBlocking {
        val activeDht = AtomicInteger(0)
        val maxActiveDht = AtomicInteger(0)
        val completedDht = AtomicInteger(0)
        val dhtDiscover: suspend (AceLiveDhtDiscoveryRequest) -> AceLiveDhtDiscoveryResult = {
            val active = activeDht.incrementAndGet()
            maxActiveDht.updateAndGet { previous -> maxOf(previous, active) }
            try {
                delay(50)
                completedDht.incrementAndGet()
                AceLiveDhtDiscoveryResult(emptyList(), 1, 0, 0)
            } finally {
                activeDht.decrementAndGet()
            }
        }
        val trackerDiscover: suspend (AceLiveUdpTrackerDiscoveryRequest) -> AceLiveUdpTrackerDiscoveryResult = {
            AceLiveUdpTrackerDiscoveryResult(
                peers = listOf(AceLiveTcpPeerEndpoint("1.1.1.1", 8401)),
                trackerAttempts = 1,
                failedTrackers = 0,
                rejectedEndpoints = 0
            )
        }
        val first = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = dhtDiscover,
            trackerDiscover = trackerDiscover,
            dhtHeadroomAvailable = { true }
        )
        val second = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = dhtDiscover,
            trackerDiscover = trackerDiscover,
            dhtHeadroomAvailable = { true }
        )

        coroutineScope {
            listOf(
                async { first.discover(request(swarm(24))) },
                async { second.discover(request(swarm(25))) }
            ).awaitAll()
        }

        assertEquals(2, completedDht.get())
        assertEquals(1, maxActiveDht.get())
    }

    @Test
    fun `critical heap pressure suppresses dht and keeps discovery controlled`() = runBlocking {
        val swarm = swarm(23)
        var dhtCalled = false
        val trackerPeer = AceLiveTcpPeerEndpoint("9.9.9.9", 8301)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                dhtCalled = true
                AceLiveDhtDiscoveryResult(emptyList(), 0, 0, 0)
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(listOf(trackerPeer), 1, 0, 0)
            },
            dhtHeadroomAvailable = { false }
        )

        val result = orchestrator.discover(request(swarm))

        assertFalse(dhtCalled)
        assertEquals(listOf(trackerPeer), result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.NOT_REQUESTED, result.dht.status)
    }

    @Test
    fun `heap guard matches 256 MiB device crash headroom`() {
        val mib = 1024L * 1024L

        assertFalse(
            aceLiveDhtHasHeapHeadroom(
                maxMemoryBytes = 256L * mib,
                totalMemoryBytes = 250L * mib,
                freeMemoryBytes = 1L * mib
            )
        )
        assertTrue(
            aceLiveDhtHasHeapHeadroom(
                maxMemoryBytes = 256L * mib,
                totalMemoryBytes = 128L * mib,
                freeMemoryBytes = 64L * mib
            )
        )
    }

    private fun request(swarmKey: AceLiveSwarmKey): AceLivePeerDiscoveryOrchestrationRequest =
        AceLivePeerDiscoveryOrchestrationRequest(
            dhtRequest = AceLiveDhtDiscoveryRequest(
                swarmKey = swarmKey,
                bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", 6881)),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(AceLiveDhtNodeId.BYTES) { 7 })
            ),
            trackerRequest = AceLiveUdpTrackerDiscoveryRequest(
                swarmKey = swarmKey,
                trackers = listOf("udp://tracker.test:6969"),
                peerId = ByteArray(AceLiveUdpTrackerCodec.PEER_ID_BYTES) { 8 },
                announcePort = 51413
            )
        )

    private fun swarm(fill: Int): AceLiveSwarmKey =
        AceLiveSwarmKey.fromBytes(ByteArray(AceLiveSwarmKey.BYTES) { fill.toByte() })
}
