package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AceLivePeerRefillQualifiedTargetStopTest {
    @Test
    fun `cycle releases later reservations once qualified target is reached`() = runBlocking {
        val existing = endpoint("192.0.2.10", 8621)
        val first = endpoint("192.0.2.20", 8621)
        val second = endpoint("192.0.2.30", 8621)
        val coordinator = coordinator()
        coordinator.ingestDiscovery(
            discovery(existing, first, second),
            nowMillis = 1_000L
        )
        val existingPlan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 1_000L,
            maxStarts = 1
        )
        assertEquals(existing, existingPlan.candidates.single().endpoint)
        coordinator.beginStart(1L, existing)
        coordinator.markStartAccepted(1L)

        val active = linkedSetOf(1L)
        val starts = mutableListOf<AceLiveTcpPeerEndpoint>()
        var nextPeerId = 10L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = { error("target completion must suppress network discovery") },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { peerId, peer ->
                starts += peer
                active += peerId
                coordinator.onPoolEvent(
                    AceLiveTcpPoolEvent.TransportConnected(peerId, reconnectAttempt = 0),
                    nowMillis = 2_000L
                )
                coordinator.onPoolEvent(
                    AceLiveTcpPoolEvent.HandshakeAccepted(peerId),
                    nowMillis = 2_001L
                )
                if (peer == first) {
                    coordinator.onPoolEvent(
                        AceLiveTcpPoolEvent.TransportConnected(1L, reconnectAttempt = 0),
                        nowMillis = 2_000L
                    )
                    coordinator.onPoolEvent(
                        AceLiveTcpPoolEvent.HandshakeAccepted(1L),
                        nowMillis = 2_001L
                    )
                }
            },
            clockMillis = { 2_000L }
        )

        val result = loop.runOneCycle(nowMillis = 2_000L)

        assertEquals(listOf(first), starts)
        assertEquals(1, result.plannedStarts)
        assertEquals(1, result.startedPeers)
        assertFalse(result.discoveryAttempted)
        assertFalse(requireNotNull(coordinator.snapshot(second)).startReserved)
    }

    private fun coordinator(): AceLivePeerRefillCoordinator = AceLivePeerRefillCoordinator(
        policy = AceLivePeerRefillPolicy(
            targetActivePeers = 2,
            maxActivePeers = 3,
            staleProbePeers = 0,
            maxStartsPerCycle = 2,
            refreshIntervalMillis = 1_000L,
            candidateTtlMillis = 60_000L,
            failureBackoffBaseMillis = 1_000L,
            failureBackoffMaxMillis = 8_000L
        )
    )

    private fun discovery(
        vararg peers: AceLiveTcpPeerEndpoint
    ): AceLivePeerDiscoveryOrchestrationResult = AceLivePeerDiscoveryOrchestrationResult(
        peers = peers.map { peer ->
            AceLiveDiscoveredPeer(
                endpoint = peer,
                sources = setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            )
        },
        dht = AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
            returnedPeerCount = peers.size
        ),
        tracker = AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
            returnedPeerCount = 0
        )
    )

    private fun endpoint(host: String, port: Int): AceLiveTcpPeerEndpoint =
        AceLiveTcpPeerEndpoint(host = host, port = port)
}
