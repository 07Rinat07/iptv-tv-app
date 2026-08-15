from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one match, found {count}: {old[:140]!r}"
        )
    target.write_text(text.replace(old, new, 1))


Path(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/"
    "AceLiveAdaptivePeerRefillPolicy.kt"
).write_text('''package com.iptv.tv.core.p2p

/**
 * Maps authoritative consumer pressure to bounded extra peer probes.
 *
 * Refill is intentionally additive only: this policy never selects or evicts an active peer.
 * Replacement needs per-peer quality evidence and remains a separate increment.
 */
internal data class AceLiveAdaptivePeerRefillSettings(
    val lowExtraPeers: Int = 1,
    val criticalExtraPeers: Int = 2,
    val hardMaxExtraPeers: Int = 2
) {
    init {
        require(lowExtraPeers >= 0) { "lowExtraPeers must be non-negative" }
        require(criticalExtraPeers >= lowExtraPeers) {
            "criticalExtraPeers must be >= lowExtraPeers"
        }
        require(criticalExtraPeers <= hardMaxExtraPeers) {
            "criticalExtraPeers must not exceed hardMaxExtraPeers"
        }
    }
}

internal class AceLiveAdaptivePeerRefillPolicy(
    private val settings: AceLiveAdaptivePeerRefillSettings =
        AceLiveAdaptivePeerRefillSettings()
) {
    fun extraProbePeersFor(pressure: AceLiveBufferPressure?): Int {
        val requested = when (pressure) {
            AceLiveBufferPressure.CRITICAL -> settings.criticalExtraPeers
            AceLiveBufferPressure.LOW -> settings.lowExtraPeers
            AceLiveBufferPressure.TARGET,
            AceLiveBufferPressure.HIGH,
            null -> 0
        }
        return requested.coerceIn(0, settings.hardMaxExtraPeers)
    }
}
''')

Path(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/"
    "AceLiveAdaptivePeerRefillPolicyTest.kt"
).write_text('''package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveAdaptivePeerRefillPolicyTest {
    @Test
    fun pressureMapsToBoundedExtraPeerProbes() {
        val policy = AceLiveAdaptivePeerRefillPolicy()

        assertEquals(2, policy.extraProbePeersFor(AceLiveBufferPressure.CRITICAL))
        assertEquals(1, policy.extraProbePeersFor(AceLiveBufferPressure.LOW))
        assertEquals(0, policy.extraProbePeersFor(AceLiveBufferPressure.TARGET))
        assertEquals(0, policy.extraProbePeersFor(AceLiveBufferPressure.HIGH))
        assertEquals(0, policy.extraProbePeersFor(null))
    }

    @Test
    fun rejectsNonMonotonicOrUnboundedSettings() {
        val nonMonotonic = runCatching {
            AceLiveAdaptivePeerRefillSettings(
                lowExtraPeers = 2,
                criticalExtraPeers = 1,
                hardMaxExtraPeers = 2
            )
        }
        val unbounded = runCatching {
            AceLiveAdaptivePeerRefillSettings(
                lowExtraPeers = 1,
                criticalExtraPeers = 3,
                hardMaxExtraPeers = 2
            )
        }

        assertTrue(nonMonotonic.isFailure)
        assertTrue(unbounded.isFailure)
    }
}
''')

# Per-peer quality evidence for the next replacement increment.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerProductionTracker.kt",
    '''data class AceLivePeerProductionSnapshot(
    val discoveredCandidates: Int,
    val connectedPeers: Int,
    val handshakedPeers: Int,
    val windowUsefulPeers: Int,
    val unchokedPeers: Int,
    val producingPeers: Int,
    val aggregateBytesPerSecond: Long,
    val freshestMediaAgeMillis: Long?
)
''',
    '''data class AceLivePeerProductionSnapshot(
    val discoveredCandidates: Int,
    val connectedPeers: Int,
    val handshakedPeers: Int,
    val windowUsefulPeers: Int,
    val unchokedPeers: Int,
    val producingPeers: Int,
    val aggregateBytesPerSecond: Long,
    val freshestMediaAgeMillis: Long?
)

data class AceLivePeerQualitySnapshot(
    val peerId: Long,
    val connected: Boolean,
    val handshaked: Boolean,
    val windowUseful: Boolean,
    val unchoked: Boolean,
    val producing: Boolean,
    val recentBytesPerSecond: Long,
    val mediaAgeMillis: Long?,
    val connectedAgeMillis: Long,
    val totalMediaBytes: Long
)
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerProductionTracker.kt",
    '''    fun snapshot(nowMillis: Long): AceLivePeerProductionSnapshot = synchronized(lock) {
        val now = nowMillis.coerceAtLeast(0L)
        val connected = peers.values.filter { it.connected }
        val handshaked = connected.filter { it.handshaked }
        val windowUseful = handshaked.filter { it.windowUseful }
        val unchoked = handshaked.filter { it.unchoked }
        val producing = handshaked.filter { peer ->
            peer.windowUseful &&
                peer.unchoked &&
                peer.lastMediaAtMillis?.let { now - it <= producingFreshnessMillis } == true
        }
        AceLivePeerProductionSnapshot(
            discoveredCandidates = discoveredCandidates,
            connectedPeers = connected.size,
            handshakedPeers = handshaked.size,
            windowUsefulPeers = windowUseful.size,
            unchokedPeers = unchoked.size,
            producingPeers = producing.size,
            aggregateBytesPerSecond = producing.fold(0L) { total, peer ->
                saturatingAdd(total, peer.ewmaBytesPerSecond.coerceAtLeast(0L))
            },
            freshestMediaAgeMillis = producing.mapNotNull { peer ->
                peer.lastMediaAtMillis?.let { (now - it).coerceAtLeast(0L) }
            }.minOrNull()
        )
    }
''',
    '''    fun snapshot(nowMillis: Long): AceLivePeerProductionSnapshot = synchronized(lock) {
        val now = nowMillis.coerceAtLeast(0L)
        val connected = peers.values.filter { it.connected }
        val handshaked = connected.filter { it.handshaked }
        val windowUseful = handshaked.filter { it.windowUseful }
        val unchoked = handshaked.filter { it.unchoked }
        val producing = handshaked.filter { peer -> isProducing(peer, now) }
        AceLivePeerProductionSnapshot(
            discoveredCandidates = discoveredCandidates,
            connectedPeers = connected.size,
            handshakedPeers = handshaked.size,
            windowUsefulPeers = windowUseful.size,
            unchokedPeers = unchoked.size,
            producingPeers = producing.size,
            aggregateBytesPerSecond = producing.fold(0L) { total, peer ->
                saturatingAdd(total, peer.ewmaBytesPerSecond.coerceAtLeast(0L))
            },
            freshestMediaAgeMillis = producing.mapNotNull { peer ->
                peer.lastMediaAtMillis?.let { (now - it).coerceAtLeast(0L) }
            }.minOrNull()
        )
    }

    fun peerSnapshots(nowMillis: Long): List<AceLivePeerQualitySnapshot> = synchronized(lock) {
        val now = nowMillis.coerceAtLeast(0L)
        peers.entries
            .map { (peerId, peer) ->
                val mediaAge = peer.lastMediaAtMillis?.let { last ->
                    (now - last).coerceAtLeast(0L)
                }
                AceLivePeerQualitySnapshot(
                    peerId = peerId,
                    connected = peer.connected,
                    handshaked = peer.connected && peer.handshaked,
                    windowUseful = peer.connected && peer.handshaked && peer.windowUseful,
                    unchoked = peer.connected && peer.handshaked && peer.unchoked,
                    producing = isProducing(peer, now),
                    recentBytesPerSecond = peer.ewmaBytesPerSecond.coerceAtLeast(0L),
                    mediaAgeMillis = mediaAge,
                    connectedAgeMillis = if (peer.connected) {
                        (now - peer.connectedAtMillis).coerceAtLeast(0L)
                    } else {
                        0L
                    },
                    totalMediaBytes = peer.totalMediaBytes.coerceAtLeast(0L)
                )
            }
            .sortedBy { snapshot -> snapshot.peerId }
    }

    private fun isProducing(peer: PeerState, nowMillis: Long): Boolean =
        peer.connected &&
            peer.handshaked &&
            peer.windowUseful &&
            peer.unchoked &&
            peer.lastMediaAtMillis?.let { nowMillis - it <= producingFreshnessMillis } == true
''',
)

replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveTcpConnectionPool.kt",
    '''    /** Latest quality snapshot for scheduler/diagnostics without exposing mutable peer internals. */
    fun peerProductionSnapshot(nowMillis: Long = clockMillis()): AceLivePeerProductionSnapshot =
        productionTracker.snapshot(nowMillis)
''',
    '''    /** Latest aggregate quality snapshot for scheduler/diagnostics. */
    fun peerProductionSnapshot(nowMillis: Long = clockMillis()): AceLivePeerProductionSnapshot =
        productionTracker.snapshot(nowMillis)

    /** Immutable per-peer evidence for future bounded replacement/scoring decisions. */
    fun peerQualitySnapshots(nowMillis: Long = clockMillis()): List<AceLivePeerQualitySnapshot> =
        productionTracker.peerSnapshots(nowMillis)
''',
)

# Extend existing refill capacity with an adaptive extra-probe request. Recovery and pressure use the
# larger request rather than adding together, so two feedback loops cannot silently double expansion.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''     * This coordinator never evicts an active peer. A stale-but-reachable pool may temporarily request
 * extra probe peers up to [AceLivePeerRefillPolicy.maxActivePeers], preserving the existing recovery
 * contract that staleness is not equivalent to peer failure.
''',
    ''' * This coordinator never evicts an active peer. Recovery staleness or authoritative low-buffer
 * pressure may temporarily request extra probe peers up to [AceLivePeerRefillPolicy.maxActivePeers].
 * When both signals are present the larger bounded probe request wins; they are not added together.
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''    fun planRefill(
        activePeerIds: Set<Long>,
        nextNeededPiece: Long?,
        poolStale: Boolean,
        nowMillis: Long
    ): AceLivePeerRefillPlan = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        require(nextNeededPiece == null || nextNeededPiece >= 0) {
            "nextNeededPiece must be non-negative when present"
        }
        syncActivePeerIdsLocked(activePeerIds)
        pruneExpiredCandidatesLocked(nowMillis)

        val desired = if (poolStale) {
            (policy.targetActivePeers + policy.staleProbePeers).coerceAtMost(policy.maxActivePeers)
        } else {
            policy.targetActivePeers
        }
''',
    '''    fun planRefill(
        activePeerIds: Set<Long>,
        nextNeededPiece: Long?,
        poolStale: Boolean,
        nowMillis: Long,
        extraProbePeers: Int = 0
    ): AceLivePeerRefillPlan = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        require(nextNeededPiece == null || nextNeededPiece >= 0) {
            "nextNeededPiece must be non-negative when present"
        }
        require(extraProbePeers >= 0) { "extraProbePeers must be non-negative" }
        syncActivePeerIdsLocked(activePeerIds)
        pruneExpiredCandidatesLocked(nowMillis)

        val recoveryProbePeers = if (poolStale) policy.staleProbePeers else 0
        val requestedProbePeers = maxOf(recoveryProbePeers, extraProbePeers)
            .coerceAtMost(policy.maxActivePeers)
        val desired = (policy.targetActivePeers + requestedProbePeers)
            .coerceAtMost(policy.maxActivePeers)
''',
)

replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''class AceLivePeerRefillLoop(
    private val coordinator: AceLivePeerRefillCoordinator,
    private val discover: suspend () -> AceLivePeerDiscoveryOrchestrationResult,
    private val activePeerIds: suspend () -> Set<Long>,
    private val evaluateRecovery: suspend () -> AceLiveRecoveryPlan,
    private val nextNeededPiece: suspend () -> Long?,
    private val allocatePeerId: () -> Long,
    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
''',
    '''class AceLivePeerRefillLoop(
    private val coordinator: AceLivePeerRefillCoordinator,
    private val discover: suspend () -> AceLivePeerDiscoveryOrchestrationResult,
    private val activePeerIds: suspend () -> Set<Long>,
    private val evaluateRecovery: suspend () -> AceLiveRecoveryPlan,
    private val nextNeededPiece: suspend () -> Long?,
    private val allocatePeerId: () -> Long,
    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val adaptiveProbePeers: suspend () -> Int = { 0 }
) {
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''        val active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        val recovery = evaluateRecovery()
        val needsDiscovery = active.size < coordinator.policy.targetActivePeers || recovery.poolStale
        if (!needsDiscovery) {
''',
    '''        val active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        val recovery = evaluateRecovery()
        val requestedAdaptiveProbePeers = adaptiveProbePeers().coerceAtLeast(0)
        val adaptiveDesired = (
            coordinator.policy.targetActivePeers + requestedAdaptiveProbePeers
        ).coerceAtMost(coordinator.policy.maxActivePeers)
        val needsDiscovery =
            active.size < coordinator.policy.targetActivePeers ||
                recovery.poolStale ||
                active.size < adaptiveDesired
        if (!needsDiscovery) {
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''        val plan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = nextNeededPiece(),
            poolStale = recovery.poolStale,
            nowMillis = nowMillis
        )
''',
    '''        val plan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = nextNeededPiece(),
            poolStale = recovery.poolStale,
            nowMillis = nowMillis,
            extraProbePeers = requestedAdaptiveProbePeers
        )
''',
)

# Runtime pressure -> persistent bounded refill demand. Increasing pressure triggers one immediate
# asynchronous cycle; normal 30-second refill continues to consume the same atomic demand.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''        private val adaptiveRequestDepthPolicy = AceLiveAdaptiveRequestDepthPolicy()
        private val schedulerRequestDepth = AtomicInteger(BASELINE_IN_FLIGHT_PER_PEER)
        private val bufferDiagnosticsReporter = AceLiveBufferDiagnosticsReporter(diagnosticsObserver)
''',
    '''        private val adaptiveRequestDepthPolicy = AceLiveAdaptiveRequestDepthPolicy()
        private val adaptivePeerRefillPolicy = AceLiveAdaptivePeerRefillPolicy()
        private val schedulerRequestDepth = AtomicInteger(BASELINE_IN_FLIGHT_PER_PEER)
        private val adaptivePeerProbePeers = AtomicInteger(0)
        private val bufferDiagnosticsReporter = AceLiveBufferDiagnosticsReporter(diagnosticsObserver)
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''            startPeer = { peerId, endpoint ->
                pool.startPeer(
                    peerId = peerId,
                    endpoint = endpoint,
                    swarmKey = transport.swarmKey.toByteArray(),
                    localPeerId = localPeerId
                )
                firstPeerStartAtMillis.compareAndSet(0L, System.currentTimeMillis())
            }
        )
''',
    '''            startPeer = { peerId, endpoint ->
                pool.startPeer(
                    peerId = peerId,
                    endpoint = endpoint,
                    swarmKey = transport.swarmKey.toByteArray(),
                    localPeerId = localPeerId
                )
                firstPeerStartAtMillis.compareAndSet(0L, System.currentTimeMillis())
            },
            adaptiveProbePeers = { adaptivePeerProbePeers.get() }
        )
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''        private fun onConsumerLifecycle(event: AceLiveConsumerLifecycleEvent) {
            val sample = authoritativeConsumerPressureTracker.onEvent(event) ?: return
            val requestDepth = adaptiveRequestDepthPolicy.depthFor(sample.pressure.pressure)
            val previousDepth = schedulerRequestDepth.getAndSet(requestDepth)
            if (previousDepth != requestDepth) {
                Log.i(
                    LOG_TAG,
                    "event=request_depth pressure=${sample.pressure.pressure} " +
                        "signal=${sample.pressure.signal} from=$previousDepth to=$requestDepth"
                )
                runCatching {
                    diagnosticsObserver(
                        "embedded_ace_live_request_depth",
                        "depth=$requestDepth, previous=$previousDepth, " +
                            "pressure=${sample.pressure.pressure}, signal=${sample.pressure.signal}"
                    )
                }
            }
            bufferDiagnosticsReporter.maybeReport(
''',
    '''        private fun onConsumerLifecycle(event: AceLiveConsumerLifecycleEvent) {
            val sample = authoritativeConsumerPressureTracker.onEvent(event) ?: return
            val pressure = sample.pressure.pressure
            val requestDepth = adaptiveRequestDepthPolicy.depthFor(pressure)
            val previousDepth = schedulerRequestDepth.getAndSet(requestDepth)
            if (previousDepth != requestDepth) {
                Log.i(
                    LOG_TAG,
                    "event=request_depth pressure=$pressure " +
                        "signal=${sample.pressure.signal} from=$previousDepth to=$requestDepth"
                )
                runCatching {
                    diagnosticsObserver(
                        "embedded_ace_live_request_depth",
                        "depth=$requestDepth, previous=$previousDepth, " +
                            "pressure=$pressure, signal=${sample.pressure.signal}"
                    )
                }
            }

            val extraProbePeers = adaptivePeerRefillPolicy.extraProbePeersFor(pressure)
            val previousProbePeers = adaptivePeerProbePeers.getAndSet(extraProbePeers)
            if (previousProbePeers != extraProbePeers) {
                Log.i(
                    LOG_TAG,
                    "event=peer_refill_pressure pressure=$pressure " +
                        "from=$previousProbePeers to=$extraProbePeers"
                )
                runCatching {
                    diagnosticsObserver(
                        "embedded_ace_live_peer_refill",
                        "extra_probe_peers=$extraProbePeers, previous=$previousProbePeers, " +
                            "pressure=$pressure"
                    )
                }
                if (extraProbePeers > previousProbePeers && !closed.get()) {
                    scope.launch {
                        runCatching { refillLoop.runOneCycle() }
                            .onFailure { error ->
                                if (!closed.get()) {
                                    Log.w(
                                        LOG_TAG,
                                        "event=adaptive_peer_refill_failed " +
                                            "reason=${error.javaClass.simpleName}"
                                    )
                                }
                            }
                    }
                }
            }
            bufferDiagnosticsReporter.maybeReport(
''',
)

# Tests: per-peer evidence and adaptive refill capacity/trigger.
replace_once(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLivePeerProductionTrackerTest.kt",
    '''    @Test
    fun `aggregate rate includes only fresh requestable producing peers`() {
''',
    '''    @Test
    fun `per peer snapshots preserve requestability production and freshness evidence`() {
        val tracker = AceLivePeerProductionTracker(
            producingFreshnessMillis = 2_000L,
            ewmaCurrentWeightPercent = 100L
        )
        tracker.onTransportConnected(peerId = 20L, nowMillis = 100L)
        tracker.onHandshakeAccepted(peerId = 20L)
        tracker.onPeerRequestability(peerId = 20L, windowUseful = true, unchoked = true)
        tracker.onMediaProduced(peerId = 20L, mediaBytes = 100_000L, nowMillis = 1_000L)
        tracker.onMediaProduced(peerId = 20L, mediaBytes = 200_000L, nowMillis = 2_000L)
        tracker.onTransportConnected(peerId = 21L, nowMillis = 500L)
        tracker.onHandshakeAccepted(peerId = 21L)
        tracker.onPeerRequestability(peerId = 21L, windowUseful = false, unchoked = true)

        val peers = tracker.peerSnapshots(nowMillis = 2_500L)

        assertEquals(listOf(20L, 21L), peers.map { it.peerId })
        val producing = peers[0]
        assertEquals(true, producing.connected)
        assertEquals(true, producing.handshaked)
        assertEquals(true, producing.windowUseful)
        assertEquals(true, producing.unchoked)
        assertEquals(true, producing.producing)
        assertEquals(200_000L, producing.recentBytesPerSecond)
        assertEquals(500L, producing.mediaAgeMillis)
        assertEquals(2_400L, producing.connectedAgeMillis)
        assertEquals(300_000L, producing.totalMediaBytes)

        val idle = peers[1]
        assertEquals(true, idle.connected)
        assertEquals(true, idle.handshaked)
        assertEquals(false, idle.windowUseful)
        assertEquals(false, idle.producing)
        assertEquals(null, idle.mediaAgeMillis)
    }

    @Test
    fun `aggregate rate includes only fresh requestable producing peers`() {
''',
)

replace_once(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinatorTest.kt",
    '''    @Test
    fun `cancelled start cleanup does not increment peer failure score`() {
''',
    '''    @Test
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
    fun `cancelled start cleanup does not increment peer failure score`() {
''',
)

# Documentation: V3e complete, V3f current and replacement intentionally deferred.
replace_once(
    "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md",
    '''Текущий V3e впервые подаёт этот authoritative pressure в scheduler behavior через bounded adaptive request depth. До первого consumer sample сохраняется прежний baseline `2` pieces/peer; `TARGET=2`, `HIGH=1`, `LOW=3`, `CRITICAL=4`. Изменение depth влияет только на новые piece assignments, не отменяет уже выданные requests и остаётся ограничено существующим reassembly/memory horizon. Peer refill, recovery timing, startup/no-peer/stall bounds и wire protocol этим инкрементом не меняются.
''',
    '''PR #116 завершил V3e bounded adaptive request depth и уже находится в `main`: authoritative pressure выбирает `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`, до первого consumer sample сохраняется baseline `2`, а снижение depth не отменяет уже выданные piece ownership. Exact-head Android CI #513, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

Текущий V3f добавляет pressure-aware bounded peer refill без эвристического eviction: `TARGET/HIGH` не расширяют normal pool, `LOW` разрешает один дополнительный probe-peer, `CRITICAL` — два, всегда в пределах существующего `maxActivePeers`. Recovery-stale и pressure probe requests не суммируются — используется больший bounded запрос. Для следующего replacement-инкремента tracker также публикует immutable per-peer quality snapshots с lifecycle/requestability/production/freshness/rate evidence. Принудительное отключение active peers, recovery timing и startup/no-peer/stall bounds этим PR не меняются.
''',
)
replace_once(
    "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md",
    '''V3d завершён как behavior-neutral lifecycle boundary. V3e добавляет первый scheduler feedback: stable authoritative pressure выбирает bounded per-peer request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`. Понижение не requeue/cancel существующие assignments, а лишь блокирует новые до естественного снижения in-flight. Верхний предел остаётся hard-capped, а общий scheduling horizon по-прежнему ограничен `maxReassemblerAheadPieces` и memory budget. Peer refill/replacement и recovery policy остаются следующими отдельными инкрементами.
''',
    '''V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил первый scheduler feedback: stable authoritative pressure выбирает bounded per-peer request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`. Понижение не requeue/cancel существующие assignments, а лишь блокирует новые до естественного снижения in-flight. V3f расширяет ту же feedback-цепочку только на bounded additive refill: `LOW +1`, `CRITICAL +2`, без eviction. Per-peer quality snapshots готовят доказательную базу для следующего отдельного replacement-инкремента, где отключение peer должно зависеть от подтверждённой деградации, а не от одного low-buffer sample.
''',
)

replace_once(
    "docs/ROADMAP.md",
    '''- PR #115 — authoritative lifecycle подключён к реальному loopback и прошёл Android CI #511 + real Torrent TV smoke.
''',
    '''- PR #115 — authoritative lifecycle подключён к реальному loopback и прошёл Android CI #511 + real Torrent TV smoke;
- PR #116 — authoritative pressure подключён к bounded request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`; Android CI #513, real smoke и signed ARM TV APK прошли успешно.
''',
)
replace_once(
    "docs/ROADMAP.md",
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3d (PR #112–#115) уже дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership и реальный loopback lifecycle. Текущий **V3e** включает первый bounded scheduler feedback: authoritative pressure меняет только per-peer request depth (`HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`) при сохранении существующих reassembly/memory caps. Peer refill/replacement, recovery timing, startup/no-peer/stall bounds и wire protocol пока не меняются.
''',
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3e (PR #112–#116) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, real loopback lifecycle и bounded adaptive request depth. Текущий **V3f** добавляет pressure-aware additive peer refill: `LOW` разрешает +1 probe-peer, `CRITICAL` +2, без eviction и только в пределах `maxActivePeers`; per-peer quality snapshot готовит следующий bounded replacement шаг. Recovery timing, startup/no-peer/stall bounds и wire protocol пока не меняются.
''',
)

# Self-clean staging helpers before committing product changes.
for helper in (
    Path(".github/scripts/apply_ace_live_pressure_aware_peer_refill_v3f.py"),
    Path(".github/workflows/apply-ace-live-pressure-aware-peer-refill-v3f.yml"),
):
    if helper.exists():
        helper.unlink()
