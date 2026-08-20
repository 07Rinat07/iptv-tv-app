package com.iptv.tv.core.p2p

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerRefillCoordinatorTest {
    @Test
    fun `verified useful window outranks dual-source unknown peer`() {
        val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
        val useful = endpoint("203.0.113.20", 8621)
        val dualSource = endpoint("203.0.113.10", 8621)

        coordinator.ingestDiscovery(
            discovery(useful to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 1_000
        )
        val initial = coordinator.planRefill(
            emptySet(),
            nextNeededPiece = 105,
            poolStale = false,
            nowMillis = 1_000
        )
        coordinator.beginStart(1, initial.candidates.single().endpoint)
        coordinator.markStartAccepted(1)
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.TransportConnected(peerId = 1, reconnectAttempt = 0),
            nowMillis = 1_050
        )
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.Ingress(
                peerId = 1,
                result = AceLivePeerIngressResult(
                    metadataUpdates = listOf(window(100, 120))
                )
            ),
            nowMillis = 1_100
        )
        coordinator.syncActivePeerIds(emptySet())

        coordinator.ingestDiscovery(
            discovery(
                dualSource to setOf(
                    AceLivePeerDiscoverySource.MAINLINE_DHT,
                    AceLivePeerDiscoverySource.UDP_TRACKER
                ),
                useful to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            ),
            nowMillis = 2_000
        )

        val plan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = 105,
            poolStale = false,
            nowMillis = 2_000
        )

        assertEquals(useful, plan.candidates.single().endpoint)
        assertEquals(window(100, 120), plan.candidates.single().advertisedWindow)
    }

    @Test
    fun `stale pool adds bounded probes but healthy full target adds none`() {
        val coordinator = coordinator(target = 2, max = 4, staleProbe = 2, maxStarts = 4)
        coordinator.ingestDiscovery(
            discovery(
                endpoint("198.51.100.1", 8001) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.2", 8002) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.3", 8003) to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val active = setOf(90L, 91L)
        val stalePlan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = 10,
            poolStale = true,
            nowMillis = 1_000
        )

        assertEquals(4, stalePlan.desiredActivePeers)
        assertEquals(2, stalePlan.candidates.size)
        assertTrue(stalePlan.staleProbe)

        stalePlan.candidates.forEach { candidate ->
            coordinator.releaseReservation(candidate.endpoint)
        }
        val healthyPlan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = 10,
            poolStale = false,
            nowMillis = 1_000
        )
        assertEquals(2, healthyPlan.desiredActivePeers)
        assertTrue(healthyPlan.candidates.isEmpty())
    }

    @Test
    fun `final failure backs off temporarily and peer becomes retryable`() {
        val coordinator = coordinator(
            target = 1,
            max = 1,
            maxStarts = 1,
            backoffBase = 5_000,
            backoffMax = 20_000
        )
        val peer = endpoint("192.0.2.1", 9000)
        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 1_000
        )
        val first = coordinator.planRefill(emptySet(), null, false, 1_000).candidates.single()
        coordinator.beginStart(7, first.endpoint)
        coordinator.markStartAccepted(7)

        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.ConnectFailed(peerId = 7, retrying = false),
            nowMillis = 2_000
        )

        val failed = assertNotNullSnapshot(coordinator, peer)
        assertEquals(1, failed.consecutiveFailures)
        assertEquals(7_000, failed.retryNotBeforeMillis)
        assertTrue(coordinator.planRefill(emptySet(), null, false, 6_999).candidates.isEmpty())

        val retry = coordinator.planRefill(emptySet(), null, false, 7_000)
        assertEquals(peer, retry.candidates.single().endpoint)
    }

    @Test
    fun `successful handshake clears failure history without banning peer`() {
        val coordinator = coordinator(
            target = 1,
            max = 1,
            maxStarts = 1,
            backoffBase = 1_000,
            backoffMax = 4_000
        )
        val peer = endpoint("192.0.2.2", 9001)
        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)),
            nowMillis = 1_000
        )
        var plan = coordinator.planRefill(emptySet(), null, false, 1_000)
        coordinator.beginStart(1, plan.candidates.single().endpoint)
        coordinator.markStartRejected(1, 1_000)

        plan = coordinator.planRefill(emptySet(), null, false, 2_000)
        coordinator.beginStart(2, plan.candidates.single().endpoint)
        coordinator.onPoolEvent(
            AceLiveTcpPoolEvent.HandshakeAccepted(peerId = 2),
            nowMillis = 2_100
        )

        val snapshot = assertNotNullSnapshot(coordinator, peer)
        assertEquals(0, snapshot.consecutiveFailures)
        assertEquals(0, snapshot.retryNotBeforeMillis)
        assertEquals(2_100L, snapshot.lastHandshakeAtMillis)
        assertEquals(2L, snapshot.managedPeerId)
    }

    @Test
    fun `reservations prevent duplicate planning before start is bound`() {
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        val first = endpoint("198.51.100.10", 8100)
        val second = endpoint("198.51.100.11", 8101)
        coordinator.ingestDiscovery(
            discovery(
                first to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                second to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val plan = coordinator.planRefill(emptySet(), null, false, 1_000)
        assertEquals(2, plan.candidates.size)
        assertTrue(coordinator.planRefill(emptySet(), null, false, 1_000).candidates.isEmpty())

        coordinator.releaseReservation(plan.candidates.first().endpoint)
        assertEquals(1, coordinator.planRefill(emptySet(), null, false, 1_000).candidates.size)
    }

    @Test
    fun `overlapping plans atomically reserve only target capacity`() {
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        coordinator.ingestDiscovery(
            discovery(
                endpoint("198.51.100.20", 8110) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.21", 8111) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.22", 8112) to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                endpoint("198.51.100.23", 8113) to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val gate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val futures = List(2) {
                executor.submit<List<AceLiveTcpPeerEndpoint>> {
                    gate.await()
                    coordinator.planRefill(
                        activePeerIds = emptySet(),
                        nextNeededPiece = null,
                        poolStale = false,
                        nowMillis = 1_000
                    ).candidates.map { candidate -> candidate.endpoint }
                }
            }
            gate.countDown()
            val selected = futures.flatMap { future ->
                future.get(5, TimeUnit.SECONDS)
            }

            assertEquals(2, selected.size)
            assertEquals(2, selected.toSet().size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `accepted pending start counts against target before active snapshot catches up`() {
        val coordinator = coordinator(target = 1, max = 2, maxStarts = 1)
        val first = endpoint("198.51.100.30", 8120)
        val second = endpoint("198.51.100.31", 8121)
        coordinator.ingestDiscovery(
            discovery(
                first to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                second to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val firstPlan = coordinator.planRefill(emptySet(), null, false, 1_000)
        coordinator.beginStart(40, firstPlan.candidates.single().endpoint)
        coordinator.markStartAccepted(40)

        val overlapping = coordinator.planRefill(emptySet(), null, false, 1_001)

        assertTrue(overlapping.candidates.isEmpty())
        assertEquals(40L, assertNotNullSnapshot(coordinator, first).managedPeerId)
    }

    @Test
    fun `expired unmanaged candidates are evicted from state`() {
        val coordinator = AceLivePeerRefillCoordinator(
            policy = AceLivePeerRefillPolicy(
                targetActivePeers = 1,
                maxActivePeers = 1,
                staleProbePeers = 0,
                maxStartsPerCycle = 1,
                refreshIntervalMillis = 1_000,
                candidateTtlMillis = 1_000,
                failureBackoffBaseMillis = 1_000,
                failureBackoffMaxMillis = 8_000
            )
        )
        val peer = endpoint("192.0.2.40", 8500)
        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 0
        )
        assertNotNull(coordinator.snapshot(peer))

        coordinator.planRefill(
            activePeerIds = setOf(99L),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 1_001
        )

        assertNull(coordinator.snapshot(peer))
        assertTrue(coordinator.snapshots().isEmpty())
    }

    @Test
    fun `healthy target skips discovery in background cycle`() = runBlocking {
        var discoveryCalls = 0
        val coordinator = coordinator(target = 2, max = 3, maxStarts = 2)
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discoveryCalls += 1
                discovery(
                    endpoint("192.0.2.10", 8200) to
                        setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { setOf(1L, 2L) },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { 10L },
            allocatePeerId = { 10L },
            startPeer = { _, _ -> error("must not start") }
        )

        val result = loop.runOneCycle(nowMillis = 1_000)

        assertFalse(result.discoveryAttempted)
        assertEquals(0, discoveryCalls)
        assertEquals(0, result.plannedStarts)
    }

    @Test
    fun `stale background refill isolates immediate start failure`() = runBlocking {
        val first = endpoint("192.0.2.20", 8300)
        val second = endpoint("192.0.2.21", 8301)
        val coordinator = coordinator(target = 1, max = 3, staleProbe = 2, maxStarts = 2)
        var nextId = 10L
        val started = mutableListOf<Long>()
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(
                    first to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                    second to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                )
            },
            activePeerIds = { setOf(99L) },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = true) },
            nextNeededPiece = { 50L },
            allocatePeerId = { nextId++ },
            startPeer = { peerId, _ ->
                if (peerId == 10L) error("synthetic immediate start failure")
                started += peerId
            },
            clockMillis = { 1_000L }
        )

        val result = loop.runOneCycle(nowMillis = 1_000)

        assertTrue(result.discoveryAttempted)
        assertEquals(2, result.plannedStarts)
        assertEquals(1, result.startedPeers)
        assertEquals(1, result.immediateStartFailures)
        assertEquals(listOf(11L), started)
        assertEquals(1, assertNotNullSnapshot(coordinator, first).consecutiveFailures)
        assertEquals(11L, assertNotNullSnapshot(coordinator, second).managedPeerId)
    }

    @Test
    fun `cancelling cycle releases current ownership and all later reservations`() = runBlocking {
        val first = endpoint("192.0.2.30", 8400)
        val second = endpoint("192.0.2.31", 8401)
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        val enteredStart = CompletableDeferred<Unit>()
        var nextId = 50L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(
                    first to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                    second to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
                )
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { nextId++ },
            startPeer = { _, _ ->
                enteredStart.complete(Unit)
                awaitCancellation()
            }
        )

        val job = launch { loop.runOneCycle(nowMillis = 1_000) }
        enteredStart.await()
        job.cancelAndJoin()

        val retry = coordinator.planRefill(emptySet(), null, false, 1_001)
        assertEquals(2, retry.candidates.size)
        assertTrue(coordinator.snapshots().all { snapshot -> snapshot.consecutiveFailures == 0 })
    }

    @Test
    fun `immediate start failure backoff uses failure clock not cycle start`() = runBlocking {
        val peer = endpoint("192.0.2.50", 8600)
        val coordinator = coordinator(
            target = 1,
            max = 1,
            maxStarts = 1,
            backoffBase = 5_000,
            backoffMax = 20_000
        )
        var clock = 10_000L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT))
            },
            activePeerIds = { emptySet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { null },
            allocatePeerId = { 60L },
            startPeer = { _, _ -> error("synthetic failure after discovery") },
            clockMillis = { clock }
        )

        val result = loop.runOneCycle(nowMillis = 1_000)
        assertEquals(1, result.immediateStartFailures)
        assertEquals(15_000L, assertNotNullSnapshot(coordinator, peer).retryNotBeforeMillis)
        assertTrue(coordinator.planRefill(emptySet(), null, false, 14_999).candidates.isEmpty())

        clock = 15_000L
        assertEquals(
            peer,
            coordinator.planRefill(emptySet(), null, false, clock).candidates.single().endpoint
        )
    }

    @Test
    fun `adaptive probes expand healthy target without requiring stale recovery`() {
        val coordinator = coordinator(target = 2, max = 4, staleProbe = 2, maxStarts = 4)
        coordinator.ingestDiscovery(
            discovery(
                endpoint("198.51.100.70", 8800) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.71", 8801) to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val plan = coordinator.planRefill(
            activePeerIds = setOf(1L, 2L),
            nextNeededPiece = 10L,
            poolStale = false,
            nowMillis = 1_000L,
            extraProbePeers = 1
        )

        assertEquals(3, plan.desiredActivePeers)
        assertEquals(1, plan.candidates.size)
        assertFalse(plan.staleProbe)
    }

    @Test
    fun `recovery and pressure probes use larger request instead of adding`() {
        val coordinator = coordinator(target = 2, max = 5, staleProbe = 2, maxStarts = 5)
        coordinator.ingestDiscovery(
            discovery(
                endpoint("198.51.100.80", 8810) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.81", 8811) to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT),
                endpoint("198.51.100.82", 8812) to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000
        )

        val plan = coordinator.planRefill(
            activePeerIds = setOf(1L, 2L),
            nextNeededPiece = 10L,
            poolStale = true,
            nowMillis = 1_000L,
            extraProbePeers = 1
        )

        assertEquals(4, plan.desiredActivePeers)
        assertEquals(2, plan.candidates.size)
        assertTrue(plan.staleProbe)
    }

    @Test
    fun `adaptive background demand triggers discovery when normal target is already full`() =
        runBlocking {
            var discoveryCalls = 0
            var nextId = 100L
            val peer = endpoint("192.0.2.70", 8900)
            val coordinator = coordinator(target = 2, max = 4, maxStarts = 2)
            val loop = AceLivePeerRefillLoop(
                coordinator = coordinator,
                discover = {
                    discoveryCalls += 1
                    discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT))
                },
                activePeerIds = { setOf(1L, 2L) },
                evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
                nextNeededPiece = { 10L },
                allocatePeerId = { nextId++ },
                startPeer = { _, _ -> Unit },
                adaptiveProbePeers = { 1 }
            )

            val result = loop.runOneCycle(nowMillis = 1_000L)

            assertTrue(result.discoveryAttempted)
            assertEquals(1, discoveryCalls)
            assertEquals(1, result.plannedStarts)
            assertEquals(1, result.startedPeers)
        }

    @Test
    fun `refill still discovers after scheduler consumed stale recovery sweep`() = runBlocking {
        val recovery = AceLiveRecoveryCoordinator(
            maxInFlightPerPeer = 1,
            policy = AceLiveRecoveryPolicy(
                requestTimeoutMillis = 4_000L,
                staleUpstreamTimeoutMillis = 12_000L,
                requestCheckIntervalMillis = 1_000L
            )
        )
        recovery.updatePeer(
            AceLivePeerWindow(
                peerId = 1L,
                minPiece = 10L,
                maxPiece = 20L,
                unchoked = true
            )
        )
        recovery.assign(nextNeeded = 10L, head = 20L, nowMillis = 0L)
        assertTrue(recovery.evaluate(nextNeeded = 10L, nowMillis = 12_000L).poolStale)

        var discoveryCalls = 0
        var nextId = 100L
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator(target = 2, max = 4, staleProbe = 2, maxStarts = 2),
            discover = {
                discoveryCalls += 1
                discovery(
                    endpoint("192.0.2.90", 8990) to
                        setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { setOf(1L, 2L) },
            evaluateRecovery = {
                recovery.evaluate(nextNeeded = 10L, nowMillis = 12_100L)
            },
            nextNeededPiece = { 10L },
            allocatePeerId = { nextId++ },
            startPeer = { _, _ -> Unit }
        )

        val result = loop.runOneCycle(nowMillis = 12_100L)

        assertTrue(result.poolStale)
        assertTrue(result.discoveryAttempted)
        assertEquals(1, discoveryCalls)
        assertEquals(1, result.startedPeers)
    }

    @Test
    fun `bounded replacement stops one peer then refills from existing adaptive demand`() = runBlocking {
        val coordinator = coordinator(target = 6, max = 8, maxStarts = 2)
        val active = linkedSetOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        var nextId = 8L
        val replacement = endpoint("192.0.2.88", 8988)
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(
                    replacement to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { 100L },
            allocatePeerId = { nextId++ },
            startPeer = { peerId, _ -> active += peerId },
            adaptiveProbePeers = { 2 },
            replacementPeerId = { _, _ -> 7L },
            stopPeer = { peerId -> active -= peerId }
        )

        val result = loop.runOneCycle(nowMillis = 10_000L)

        assertEquals(7L, result.replacedPeerId)
        assertTrue(result.discoveryAttempted)
        assertEquals(1, result.plannedStarts)
        assertEquals(1, result.startedPeers)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 8L), active)
    }

    @Test
    fun `cancelled start cleanup does not increment peer failure score`() {
        val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
        val peer = endpoint("192.0.2.60", 8700)
        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 1_000
        )
        val plan = coordinator.planRefill(emptySet(), null, false, 1_000)
        coordinator.beginStart(5, plan.candidates.single().endpoint)

        coordinator.cancelStart(5)

        val snapshot = assertNotNullSnapshot(coordinator, peer)
        assertEquals(0, snapshot.consecutiveFailures)
        assertEquals(0, snapshot.retryNotBeforeMillis)
        assertEquals(null, snapshot.managedPeerId)
    }

    private fun coordinator(
        target: Int,
        max: Int,
        staleProbe: Int = 0,
        maxStarts: Int,
        backoffBase: Long = 1_000,
        backoffMax: Long = 8_000
    ): AceLivePeerRefillCoordinator = AceLivePeerRefillCoordinator(
        policy = AceLivePeerRefillPolicy(
            targetActivePeers = target,
            maxActivePeers = max,
            staleProbePeers = staleProbe,
            maxStartsPerCycle = maxStarts,
            refreshIntervalMillis = 1_000,
            candidateTtlMillis = 60_000,
            failureBackoffBaseMillis = backoffBase,
            failureBackoffMaxMillis = backoffMax
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

    private fun window(min: Long, max: Long): AceLivePeerAdvertisedWindow =
        AceLivePeerAdvertisedWindow(
            minPiece = min,
            maxPiece = max,
            position = null,
            distanceFromSource = null,
            minPieceExplicit = true
        )

    private fun assertNotNullSnapshot(
        coordinator: AceLivePeerRefillCoordinator,
        endpoint: AceLiveTcpPeerEndpoint
    ): AceLivePeerRefillSnapshot {
        val snapshot = coordinator.snapshot(endpoint)
        assertNotNull(snapshot)
        return requireNotNull(snapshot)
    }
}
