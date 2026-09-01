package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerKnownCandidateFastRefillTest {
    @Test
    fun `pex candidate starts before network discovery`() = runBlocking {
        val known = endpoint("203.0.113.10", 8621)
        val discovered = endpoint("203.0.113.20", 8621)
        val coordinator = coordinator(target = 2, max = 3, maxStarts = 2)
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.Ingress(
                peerId = 70,
                result = AceLivePeerIngressResult(peerExchangePeers = listOf(known))
            ),
            nowMillis = 1_000L
        )

        val actions = mutableListOf<String>()
        var nextPeerId = 100L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                actions += "discover"
                discovery(
                    discovered to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { _, endpoint -> actions += "start:${endpoint.host}:${endpoint.port}" },
            clockMillis = { 1_000L }
        )

        val result = loop.runOneCycle(nowMillis = 1_000L)

        assertEquals(
            listOf(
                "start:${known.host}:${known.port}",
                "discover",
                "start:${discovered.host}:${discovered.port}"
            ),
            actions
        )
        assertTrue(result.discoveryAttempted)
        assertEquals(2, result.plannedStarts)
        assertEquals(2, result.startedPeers)
        assertEquals(0, result.immediateStartFailures)
    }

    @Test
    fun `learned and discovered peers share one bounded start budget`() = runBlocking {
        val known = endpoint("203.0.113.30", 8621)
        val discoveredA = endpoint("203.0.113.40", 8621)
        val discoveredB = endpoint("203.0.113.41", 8621)
        val coordinator = coordinator(target = 3, max = 4, maxStarts = 2)
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.Ingress(
                peerId = 80,
                result = AceLivePeerIngressResult(peerExchangePeers = listOf(known))
            ),
            nowMillis = 2_000L
        )

        val started = mutableListOf<AceLiveTcpPeerEndpoint>()
        var nextPeerId = 200L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(
                    discoveredA to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                    discoveredB to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                )
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextPeerId++ },
            startPeer = { _, endpoint -> started += endpoint },
            clockMillis = { 2_000L }
        )

        val result = loop.runOneCycle(nowMillis = 2_000L)

        assertEquals(2, result.plannedStarts)
        assertEquals(2, result.startedPeers)
        assertEquals(known, started.first())
        assertEquals(2, started.toSet().size)
        assertTrue(started.drop(1).single() in setOf(discoveredA, discoveredB))
    }

    @Test
    fun `empty learned set preserves discovery first acquisition`() = runBlocking {
        val discovered = endpoint("198.51.100.20", 9000)
        val coordinator = coordinator(target = 1, max = 2, maxStarts = 1)
        val actions = mutableListOf<String>()
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                actions += "discover"
                discovery(
                    discovered to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                )
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { 300L },
            startPeer = { _, endpoint -> actions += "start:${endpoint.host}:${endpoint.port}" },
            clockMillis = { 3_000L }
        )

        val result = loop.runOneCycle(nowMillis = 3_000L)

        assertEquals(
            listOf("discover", "start:${discovered.host}:${discovered.port}"),
            actions
        )
        assertEquals(1, result.plannedStarts)
        assertEquals(1, result.startedPeers)
    }

    private fun coordinator(
        target: Int,
        max: Int,
        maxStarts: Int
    ): AceLivePeerRefillCoordinator = AceLivePeerRefillCoordinator(
        policy = AceLivePeerRefillPolicy(
            targetActivePeers = target,
            maxActivePeers = max,
            staleProbePeers = 1,
            maxStartsPerCycle = maxStarts,
            refreshIntervalMillis = 1_000L,
            candidateTtlMillis = 60_000L,
            failureBackoffBaseMillis = 5_000L,
            failureBackoffMaxMillis = 20_000L
        )
    )

    private fun discovery(
        vararg peers: Pair<AceLiveTcpPeerEndpoint, Set<AceLivePeerDiscoverySource>>
    ): AceLivePeerDiscoveryOrchestrationResult {
        val values = peers.map { (endpoint, sources) ->
            AceLiveDiscoveredPeer(endpoint = endpoint, sources = sources)
        }
        val dhtCount = values.count { AceLivePeerDiscoverySource.MAINLINE_DHT in it.sources }
        val trackerCount = values.count { AceLivePeerDiscoverySource.UDP_TRACKER in it.sources }
        return AceLivePeerDiscoveryOrchestrationResult(
            peers = values,
            dht = sourceSummary(dhtCount),
            tracker = sourceSummary(trackerCount)
        )
    }

    private fun sourceSummary(count: Int): AceLivePeerDiscoverySourceSummary =
        if (count == 0) {
            AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
                returnedPeerCount = 0
            )
        } else {
            AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
                returnedPeerCount = count
            )
        }

    private fun endpoint(host: String, port: Int): AceLiveTcpPeerEndpoint =
        AceLiveTcpPeerEndpoint(host = host, port = port)
}
