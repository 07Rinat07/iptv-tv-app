package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AceLivePeerDiscoveryOrchestratorTest {
    @Test
    fun `deduplicates dht and tracker peers while preserving source provenance`() = runBlocking {
        val swarm = swarm(1)
        val shared = AceLiveTcpPeerEndpoint("8.8.8.8", 8621)
        val dhtOnly = AceLiveTcpPeerEndpoint("1.1.1.1", 8622)
        val trackerOnly = AceLiveTcpPeerEndpoint("9.9.9.9", 8623)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                AceLiveDhtDiscoveryResult(
                    peers = listOf(dhtOnly, shared),
                    queriesSent = 2,
                    failedQueries = 0,
                    rejectedEndpoints = 0
                )
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(
                    peers = listOf(shared, trackerOnly),
                    attemptedTrackers = 1,
                    failedTrackers = 0,
                    rejectedTrackers = 0
                )
            }
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = dhtRequest(swarm),
                trackerRequest = trackerRequest(swarm)
            )
        )

        assertEquals(listOf(dhtOnly, shared, trackerOnly), result.tcpEndpoints())
        assertEquals(setOf(AceLivePeerDiscoverySource.MAINLINE_DHT), result.peers[0].sources)
        assertEquals(
            setOf(
                AceLivePeerDiscoverySource.MAINLINE_DHT,
                AceLivePeerDiscoverySource.UDP_TRACKER
            ),
            result.peers[1].sources
        )
        assertEquals(setOf(AceLivePeerDiscoverySource.UDP_TRACKER), result.peers[2].sources)
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.dht.status)
        assertEquals(2, result.dht.returnedPeerCount)
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.tracker.status)
        assertEquals(2, result.tracker.returnedPeerCount)
    }

    @Test
    fun `tracker is not called when no real tracker request is supplied`() = runBlocking {
        var trackerCalled = false
        val peer = AceLiveTcpPeerEndpoint("8.8.4.4", 8621)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                AceLiveDhtDiscoveryResult(
                    peers = listOf(peer),
                    queriesSent = 1,
                    failedQueries = 0,
                    rejectedEndpoints = 0
                )
            },
            trackerDiscover = {
                trackerCalled = true
                AceLiveUdpTrackerDiscoveryResult(emptyList(), 0, 0, 0)
            }
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(dhtRequest = dhtRequest(swarm(2)))
        )

        assertFalse(trackerCalled)
        assertEquals(listOf(peer), result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.NOT_REQUESTED, result.tracker.status)
        assertEquals(0, result.tracker.returnedPeerCount)
    }

    @Test
    fun `ordinary failure of one source does not cancel the other source`() = runBlocking {
        val swarm = swarm(3)
        val peer = AceLiveTcpPeerEndpoint("9.9.9.9", 9000)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = { throw IllegalStateException("simulated DHT failure") },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(
                    peers = listOf(peer),
                    attemptedTrackers = 1,
                    failedTrackers = 0,
                    rejectedTrackers = 0
                )
            }
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = dhtRequest(swarm),
                trackerRequest = trackerRequest(swarm)
            )
        )

        assertEquals(listOf(peer), result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.FAILED, result.dht.status)
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.tracker.status)
    }

    @Test
    fun `mismatched swarm keys are rejected before discovery`() {
        try {
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = dhtRequest(swarm(4)),
                trackerRequest = trackerRequest(swarm(5))
            )
            fail("Expected mismatched swarm keys to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `global peer cap is deterministic and still merges provenance for included duplicates`() = runBlocking {
        val swarm = swarm(6)
        val first = AceLiveTcpPeerEndpoint("1.1.1.1", 1001)
        val shared = AceLiveTcpPeerEndpoint("8.8.8.8", 1002)
        val overflow = AceLiveTcpPeerEndpoint("9.9.9.9", 1003)
        val orchestrator = AceLivePeerDiscoveryOrchestrator(
            dhtDiscover = {
                AceLiveDhtDiscoveryResult(listOf(first, shared), 1, 0, 0)
            },
            trackerDiscover = {
                AceLiveUdpTrackerDiscoveryResult(listOf(shared, overflow), 1, 0, 0)
            },
            policy = AceLivePeerDiscoveryOrchestrationPolicy(maxTotalPeers = 2)
        )

        val result = orchestrator.discover(
            AceLivePeerDiscoveryOrchestrationRequest(
                dhtRequest = dhtRequest(swarm),
                trackerRequest = trackerRequest(swarm)
            )
        )

        assertEquals(listOf(first, shared), result.tcpEndpoints())
        assertTrue(AceLivePeerDiscoverySource.UDP_TRACKER in result.peers[1].sources)
        assertFalse(result.tcpEndpoints().contains(overflow))
    }

    @Test
    fun `at least one discovery source is required`() {
        try {
            AceLivePeerDiscoveryOrchestrationRequest()
            fail("Expected an empty orchestration request to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
    }

    private fun swarm(fill: Int): AceLiveSwarmKey =
        AceLiveSwarmKey.fromBytes(ByteArray(AceLiveSwarmKey.BYTES) { fill.toByte() })

    private fun dhtRequest(swarmKey: AceLiveSwarmKey): AceLiveDhtDiscoveryRequest =
        AceLiveDhtDiscoveryRequest(
            swarmKey = swarmKey,
            bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", 6881)),
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(AceLiveDhtNodeId.BYTES) { 7 })
        )

    private fun trackerRequest(swarmKey: AceLiveSwarmKey): AceLiveUdpTrackerDiscoveryRequest =
        AceLiveUdpTrackerDiscoveryRequest(
            swarmKey = swarmKey,
            trackers = listOf("udp://tracker.test:6969"),
            peerId = ByteArray(AceLiveUdpTrackerCodec.PEER_ID_BYTES) { 8 },
            announcePort = 51413
        )
}
