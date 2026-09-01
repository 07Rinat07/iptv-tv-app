package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerRefillKnownCandidateFastPathTest {
    @Test
    fun `known pex candidate starts before hard-full discovery refresh without extra socket`() =
        runBlocking {
            val known = endpoint("192.0.2.10", 8621)
            val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
            ingestPex(coordinator, sourcePeerId = 40L, peer = known)
            val active = linkedSetOf<Long>()
            val events = mutableListOf<String>()
            var nextPeerId = 100L

            val loop = AceLivePeerRefillLoop(
                coordinator = coordinator,
                discover = {
                    events += "discover"
                    discovery(endpoint("192.0.2.11", 8621))
                },
                activePeerIds = { active.toSet() },
                evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
                nextNeededPiece = { null },
                allocatePeerId = { nextPeerId++ },
                startPeer = { peerId, peer ->
                    events += "start:${peer.host}"
                    active += peerId
                },
                clockMillis = { 1_000L }
            )

            val result = loop.runOneCycle(nowMillis = 1_000L)

            assertEquals(listOf("start:${known.host}", "discover"), events)
            assertTrue(result.discoveryAttempted)
            assertEquals(1, result.plannedStarts)
            assertEquals(1, result.startedPeers)
        }

    @Test
    fun `known candidate starts before discovery fills only remaining cycle budget`() = runBlocking {
        val known = endpoint("192.0.2.20", 8621)
        val discovered = endpoint("192.0.2.21", 8621)
        val coordinator = coordinator(target = 2, max = 3, maxStarts = 2)
        ingestPex(coordinator, sourcePeerId = 41L, peer = known)
        val active = linkedSetOf<Long>()
        val events = mutableListOf<String>()
        var nextPeerId = 200L

        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                events += "discover"
                discovery(discovered)
            },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { peerId, peer ->
                events += "start:${peer.host}"
                active += peerId
            },
            clockMillis = { 2_000L }
        )

        val result = loop.runOneCycle(nowMillis = 2_000L)

        assertEquals(
            listOf("start:${known.host}", "discover", "start:${discovered.host}"),
            events
        )
        assertTrue(result.discoveryAttempted)
        assertEquals(2, result.plannedStarts)
        assertEquals(2, result.startedPeers)
    }

    @Test
    fun `known start failure cannot expand total starts beyond cycle cap`() = runBlocking {
        val known = endpoint("192.0.2.30", 8621)
        val discoveredOne = endpoint("192.0.2.31", 8621)
        val discoveredTwo = endpoint("192.0.2.32", 8621)
        val coordinator = coordinator(target = 3, max = 5, maxStarts = 2)
        ingestPex(coordinator, sourcePeerId = 42L, peer = known)
        val active = linkedSetOf<Long>()
        val starts = mutableListOf<AceLiveTcpPeerEndpoint>()
        var nextPeerId = 300L

        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = { discovery(discoveredOne, discoveredTwo) },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { peerId, peer ->
                starts += peer
                if (peer == known) error("synthetic known-peer start failure")
                active += peerId
            },
            clockMillis = { 3_000L }
        )

        val result = loop.runOneCycle(nowMillis = 3_000L)

        assertEquals(2, starts.size)
        assertEquals(known, starts.first())
        assertEquals(2, result.plannedStarts)
        assertEquals(1, result.startedPeers)
        assertEquals(1, result.immediateStartFailures)
    }

    private fun coordinator(
        target: Int,
        max: Int,
        maxStarts: Int
    ): AceLivePeerRefillCoordinator = AceLivePeerRefillCoordinator(
        policy = AceLivePeerRefillPolicy(
            targetActivePeers = target,
            maxActivePeers = max,
            staleProbePeers = 0,
            maxStartsPerCycle = maxStarts,
            refreshIntervalMillis = 1_000L,
            candidateTtlMillis = 60_000L,
            failureBackoffBaseMillis = 1_000L,
            failureBackoffMaxMillis = 8_000L
        )
    )

    private fun ingestPex(
        coordinator: AceLivePeerRefillCoordinator,
        sourcePeerId: Long,
        peer: AceLiveTcpPeerEndpoint
    ) {
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.Ingress(
                peerId = sourcePeerId,
                result = AceLivePeerIngressResult(peerExchangePeers = listOf(peer))
            ),
            nowMillis = 1_000L
        )
    }

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
