package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveQualifiedPeerRefillTest {
    @Test
    fun `owned target without handshakes still refills alternatives within hard capacity`() {
        val coordinator = coordinator(target = 2, max = 4, maxStarts = 2)
        val first = endpoint("198.51.100.1", 8201)
        val second = endpoint("198.51.100.2", 8202)
        val alternativeA = endpoint("198.51.100.3", 8203)
        val alternativeB = endpoint("198.51.100.4", 8204)
        manageConnectedUnqualifiedPeers(coordinator, listOf(first, second))

        coordinator.ingestDiscovery(
            discovery(
                alternativeA to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                alternativeB to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            ),
            nowMillis = 2_000L
        )

        val plan = coordinator.planRefill(
            activePeerIds = setOf(1L, 2L),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 2_000L
        )

        assertEquals(0, coordinator.qualifiedActivePeerCount(setOf(1L, 2L)))
        assertEquals(2, plan.activePeers)
        assertEquals(2, plan.candidates.size)
        assertEquals(setOf(alternativeA, alternativeB), plan.candidates.map { it.endpoint }.toSet())
    }

    @Test
    fun `handshaked target suppresses unnecessary discovery`() = runBlocking {
        val coordinator = coordinator(target = 2, max = 4, maxStarts = 2)
        val peers = listOf(
            endpoint("198.51.100.10", 8210),
            endpoint("198.51.100.11", 8211)
        )
        manageConnectedUnqualifiedPeers(coordinator, peers)
        coordinator.onPoolEvent(AceLiveTcpPoolEvent.HandshakeAccepted(1L), nowMillis = 1_100L)
        coordinator.onPoolEvent(AceLiveTcpPoolEvent.HandshakeAccepted(2L), nowMillis = 1_101L)
        var discoveryCalls = 0
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                discovery(
                    endpoint("198.51.100.12", 8212) to
                        setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { setOf(1L, 2L) },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { 3L },
            startPeer = { _, _ -> error("qualified target must not start another peer") }
        )

        val result = loop.runOneCycle(nowMillis = 2_000L)

        assertEquals(2, coordinator.qualifiedActivePeerCount(setOf(1L, 2L)))
        assertFalse(result.discoveryAttempted)
        assertEquals(0, discoveryCalls)
        assertEquals(0, result.plannedStarts)
    }

    @Test
    fun `hard owned cap prevents eleventh socket while qualification deficit remains`() = runBlocking {
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        val peers = listOf(
            endpoint("198.51.100.20", 8220),
            endpoint("198.51.100.21", 8221)
        )
        manageConnectedUnqualifiedPeers(coordinator, peers)
        var discoveryCalls = 0
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                discovery(
                    endpoint("198.51.100.22", 8222) to
                        setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                    endpoint("198.51.100.23", 8223) to
                        setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                )
            },
            activePeerIds = { setOf(1L, 2L) },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { error("hard cap must prevent allocation") },
            startPeer = { _, _ -> error("hard cap must prevent start") }
        )

        val result = loop.runOneCycle(nowMillis = 2_000L)

        assertEquals(0, coordinator.qualifiedActivePeerCount(setOf(1L, 2L)))
        assertTrue(result.discoveryAttempted)
        assertEquals(1, discoveryCalls)
        assertEquals(0, result.plannedStarts)
        assertEquals(0, result.startedPeers)
    }

    @Test
    fun `rejected handshake immediately reopens qualification demand`() {
        val coordinator = coordinator(target = 1, max = 2, maxStarts = 1)
        val peer = endpoint("198.51.100.30", 8230)
        val alternative = endpoint("198.51.100.31", 8231)
        manageConnectedUnqualifiedPeers(coordinator, listOf(peer))
        coordinator.onPoolEvent(AceLiveTcpPoolEvent.HandshakeAccepted(1L), nowMillis = 1_100L)
        assertEquals(1, coordinator.qualifiedActivePeerCount(setOf(1L)))

        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.HandshakeRejected(
                peerId = 1L,
                reason = AceLivePeerHandshakeRejectReason.SWARM_KEY_MISMATCH
            ),
            nowMillis = 1_200L
        )
        coordinator.ingestDiscovery(
            discovery(alternative to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 1_300L
        )

        val plan = coordinator.planRefill(
            activePeerIds = setOf(1L),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 1_300L
        )

        assertEquals(0, coordinator.qualifiedActivePeerCount(setOf(1L)))
        assertEquals(listOf(alternative), plan.candidates.map { it.endpoint })
    }

    private fun manageConnectedUnqualifiedPeers(
        coordinator: AceLivePeerRefillCoordinator,
        endpoints: List<AceLiveTcpPeerEndpoint>
    ) {
        coordinator.ingestDiscovery(
            discovery(
                *endpoints.map { endpoint ->
                    endpoint to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                }.toTypedArray()
            ),
            nowMillis = 1_000L
        )
        val initial = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 1_000L
        )
        assertEquals(endpoints.size, initial.candidates.size)
        initial.candidates.forEachIndexed { index, candidate ->
            val peerId = index + 1L
            coordinator.beginStart(peerId, candidate.endpoint)
            coordinator.markStartAccepted(peerId)
            coordinator.onPoolEvent(
                AceLiveTcpPoolEvent.TransportConnected(peerId = peerId, reconnectAttempt = 0),
                nowMillis = 1_050L + index
            )
        }
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
