package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive

/** Local-only refill/scoring policy. None of these values are Ace protocol fields. */
data class AceLivePeerRefillPolicy(
    val targetActivePeers: Int = 6,
    val maxActivePeers: Int = 10,
    val staleProbePeers: Int = 2,
    val maxStartsPerCycle: Int = 2,
    val refreshIntervalMillis: Long = 30_000L,
    val candidateTtlMillis: Long = 120_000L,
    val failureBackoffBaseMillis: Long = 5_000L,
    val failureBackoffMaxMillis: Long = 120_000L
) {
    init {
        require(targetActivePeers in 1..256) { "targetActivePeers must be in 1..256" }
        require(maxActivePeers in targetActivePeers..256) {
            "maxActivePeers must be >= targetActivePeers and <= 256"
        }
        require(staleProbePeers in 0..maxActivePeers) { "staleProbePeers is out of range" }
        require(maxStartsPerCycle in 1..64) { "maxStartsPerCycle must be in 1..64" }
        require(refreshIntervalMillis in 1_000L..10 * 60_000L) {
            "refreshIntervalMillis must be in 1s..10m"
        }
        require(candidateTtlMillis >= refreshIntervalMillis) {
            "candidateTtlMillis must be >= refreshIntervalMillis"
        }
        require(failureBackoffBaseMillis > 0) { "failureBackoffBaseMillis must be positive" }
        require(failureBackoffMaxMillis >= failureBackoffBaseMillis) {
            "failureBackoffMaxMillis must be >= failureBackoffBaseMillis"
        }
    }
}

data class AceLivePeerRefillCandidate(
    val endpoint: AceLiveTcpPeerEndpoint,
    val sources: Set<AceLivePeerDiscoverySource>,
    val advertisedWindow: AceLivePeerAdvertisedWindow?,
    val consecutiveFailures: Int,
    val hasSuccessfulHandshake: Boolean
)

data class AceLivePeerRefillPlan(
    val candidates: List<AceLivePeerRefillCandidate>,
    val activePeers: Int,
    val desiredActivePeers: Int,
    val staleProbe: Boolean
)

data class AceLivePeerRefillSnapshot(
    val endpoint: AceLiveTcpPeerEndpoint,
    val sources: Set<AceLivePeerDiscoverySource>,
    val advertisedWindow: AceLivePeerAdvertisedWindow?,
    val consecutiveFailures: Int,
    val retryNotBeforeMillis: Long,
    val lastDiscoveredAtMillis: Long,
    val lastHandshakeAtMillis: Long?,
    val managedPeerId: Long?,
    val startReserved: Boolean,
    val startInProgress: Boolean
)

data class AceLivePeerRefillCycleResult(
    val discoveryAttempted: Boolean,
    val plannedStarts: Int,
    val startedPeers: Int,
    val immediateStartFailures: Int,
    val poolStale: Boolean
)

/**
 * Candidate state/ranking for background Ace Live peer refill.
 *
 * Ranking deliberately gives verified live-window usefulness precedence over discovery provenance.
 * A peer found by both DHT and a tracker only wins a tie after window usefulness, failure history,
 * and successful-handshake history. Final failures use bounded exponential backoff; no candidate is
 * permanently banned by this layer.
 *
 * All mutable candidate/reservation/ownership state is serialized under one local monitor. This
 * keeps selection plus reservation atomic when background refill cycles overlap on different
 * dispatcher threads without giving this policy layer ownership of coroutines or sockets.
 *
 * This coordinator never evicts an active peer. A stale-but-reachable pool may temporarily request
 * extra probe peers up to [AceLivePeerRefillPolicy.maxActivePeers], preserving the existing recovery
 * contract that staleness is not equivalent to peer failure.
 */
class AceLivePeerRefillCoordinator(
    val policy: AceLivePeerRefillPolicy = AceLivePeerRefillPolicy()
) {
    private val stateLock = Any()
    private val candidates = LinkedHashMap<String, CandidateState>()
    private val peerIdToEndpointKey = HashMap<Long, String>()

    fun ingestDiscovery(
        result: AceLivePeerDiscoveryOrchestrationResult,
        nowMillis: Long
    ) = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        pruneExpiredCandidatesLocked(nowMillis)
        for (peer in result.peers) {
            val key = endpointKey(peer.endpoint)
            val state = candidates.getOrPut(key) {
                CandidateState(endpoint = peer.endpoint)
            }
            state.sources.clear()
            state.sources.addAll(peer.sources)
            state.lastDiscoveredAtMillis = nowMillis
        }
    }

    /**
     * Synchronizes local ownership with the real pool without treating disappearance as a failure.
     * Actual failures are scored only from final pool events or an immediate start rejection.
     */
    fun syncActivePeerIds(activePeerIds: Set<Long>) = withStateLock {
        syncActivePeerIdsLocked(activePeerIds)
    }

    /**
     * Produces and reserves a bounded set of candidates. Selection and reservation occur under the
     * same lock, so overlapping cycles cannot reserve the same endpoint. Existing reservations and
     * managed starts that are not yet visible in [activePeerIds] count against the desired capacity.
     */
    fun planRefill(
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
        val reservedPending = candidates.values.count { state -> state.startReserved }
        val managedPending = peerIdToEndpointKey.keys.count { peerId -> peerId !in activePeerIds }
        val committedPeers = activePeerIds.size + reservedPending + managedPending
        val slots = (desired - committedPeers)
            .coerceAtLeast(0)
            .coerceAtMost(policy.maxStartsPerCycle)
        if (slots == 0) {
            return@withStateLock AceLivePeerRefillPlan(
                candidates = emptyList(),
                activePeers = activePeerIds.size,
                desiredActivePeers = desired,
                staleProbe = poolStale
            )
        }

        val ranked = candidates.values
            .asSequence()
            .filter { state -> state.managedPeerId == null }
            .filter { state -> !state.startReserved && !state.startInProgress }
            .filter { state -> nowMillis >= state.retryNotBeforeMillis }
            .sortedWith(candidateComparator(nextNeededPiece))
            .take(slots)
            .toList()

        ranked.forEach { state -> state.startReserved = true }
        AceLivePeerRefillPlan(
            candidates = ranked.map(CandidateState::toPublicCandidate),
            activePeers = activePeerIds.size,
            desiredActivePeers = desired,
            staleProbe = poolStale
        )
    }

    /** Binds a reserved endpoint to a concrete pool peer id before invoking startPeer. */
    fun beginStart(peerId: Long, endpoint: AceLiveTcpPeerEndpoint) = withStateLock {
        require(peerId >= 0) { "peerId must be non-negative" }
        require(peerId !in peerIdToEndpointKey) { "peerId $peerId is already managed" }
        val key = endpointKey(endpoint)
        val state = candidates[key] ?: error("Unknown refill candidate $key")
        require(state.startReserved) { "Candidate $key was not reserved by a refill plan" }
        require(state.managedPeerId == null) { "Candidate $key is already managed" }
        state.startReserved = false
        state.startInProgress = true
        state.managedPeerId = peerId
        peerIdToEndpointKey[peerId] = key
    }

    /**
     * Called after startPeer has accepted ownership. Keep the start pending until either an active
     * pool snapshot or a pool lifecycle event confirms that ownership is visible to the transport.
     */
    fun markStartAccepted(peerId: Long) = withStateLock {
        stateForPeerLocked(peerId)?.let { state ->
            check(state.managedPeerId == peerId) { "Accepted start lost managed ownership" }
        }
    }

    /** Immediate registration/start failure; applies temporary backoff, never a permanent ban. */
    fun markStartRejected(peerId: Long, nowMillis: Long) = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        val state = stateForPeerLocked(peerId) ?: return@withStateLock
        recordFailureLocked(state, nowMillis)
        clearManagedPeerLocked(peerId, state)
    }

    /** Cancellation is caller lifecycle, not evidence that the peer itself failed. */
    fun cancelStart(peerId: Long) = withStateLock {
        val state = stateForPeerLocked(peerId) ?: return@withStateLock
        clearManagedPeerLocked(peerId, state)
    }

    /** Releases a reservation if the caller elects not to start a planned endpoint. */
    fun releaseReservation(endpoint: AceLiveTcpPeerEndpoint) = withStateLock {
        candidates[endpointKey(endpoint)]?.startReserved = false
    }

    /**
     * Pool events update only evidence relevant to refill ranking. Retry-internal failures are not
     * scored until the TCP pool reports that it has exhausted its own retry loop.
     */
    fun onPoolEvent(event: AceLiveTcpPoolEvent, nowMillis: Long) = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        val state = stateForPeerLocked(event.peerId) ?: return@withStateLock
        when (event) {
            is AceLiveTcpPoolEvent.TransportConnected -> {
                state.startInProgress = false
            }

            is AceLiveTcpPoolEvent.HandshakeAccepted -> {
                state.startInProgress = false
                state.consecutiveFailures = 0
                state.retryNotBeforeMillis = 0
                state.lastHandshakeAtMillis = nowMillis
            }

            is AceLiveTcpPoolEvent.Ingress -> {
                event.result.metadataUpdates.lastOrNull()?.let { window ->
                    state.advertisedWindow = window
                }
            }

            is AceLiveTcpPoolEvent.ConnectFailed -> {
                if (!event.retrying) {
                    recordFailureLocked(state, nowMillis)
                    clearManagedPeerLocked(event.peerId, state)
                }
            }

            is AceLiveTcpPoolEvent.Disconnected -> {
                if (!event.retrying) {
                    recordFailureLocked(state, nowMillis)
                    clearManagedPeerLocked(event.peerId, state)
                }
            }

            is AceLiveTcpPoolEvent.HandshakeRejected -> Unit
        }
    }

    fun snapshot(endpoint: AceLiveTcpPeerEndpoint): AceLivePeerRefillSnapshot? = withStateLock {
        candidates[endpointKey(endpoint)]?.toSnapshot()
    }

    fun snapshots(): List<AceLivePeerRefillSnapshot> = withStateLock {
        candidates.values.map(CandidateState::toSnapshot)
    }

    private fun syncActivePeerIdsLocked(activePeerIds: Set<Long>) {
        for (peerId in activePeerIds) {
            stateForPeerLocked(peerId)?.startInProgress = false
        }

        val staleMappings = peerIdToEndpointKey.keys.filter { peerId -> peerId !in activePeerIds }
        for (peerId in staleMappings) {
            val key = peerIdToEndpointKey[peerId] ?: continue
            val state = candidates[key]
            if (state == null) {
                peerIdToEndpointKey.remove(peerId)
                continue
            }
            if (state.startInProgress) continue
            if (state.managedPeerId == peerId) state.managedPeerId = null
            peerIdToEndpointKey.remove(peerId)
        }
    }

    private fun pruneExpiredCandidatesLocked(nowMillis: Long) {
        val expiredKeys = candidates.entries
            .asSequence()
            .filter { (_, state) ->
                state.managedPeerId == null &&
                    !state.startReserved &&
                    !state.startInProgress &&
                    elapsedSince(state.lastDiscoveredAtMillis, nowMillis) > policy.candidateTtlMillis
            }
            .map { (key, _) -> key }
            .toList()
        expiredKeys.forEach(candidates::remove)
    }

    private fun candidateComparator(nextNeededPiece: Long?): Comparator<CandidateState> =
        Comparator { left, right ->
            compareValuesBy(
                left,
                right,
                { state -> windowUsefulnessRank(state.advertisedWindow, nextNeededPiece) },
                { state -> state.consecutiveFailures },
                { state -> if (state.lastHandshakeAtMillis != null) 0 else 1 },
                { state -> -state.sources.size },
                { state -> -state.lastDiscoveredAtMillis },
                { state -> state.endpoint.host },
                { state -> state.endpoint.port }
            )
        }

    private fun windowUsefulnessRank(
        window: AceLivePeerAdvertisedWindow?,
        nextNeededPiece: Long?
    ): Int {
        if (nextNeededPiece == null) return if (window != null) 0 else 1
        if (window == null) return 1
        return if (nextNeededPiece in window.minPiece..window.maxPiece) 0 else 2
    }

    private fun recordFailureLocked(state: CandidateState, nowMillis: Long) {
        state.consecutiveFailures = (state.consecutiveFailures + 1)
            .coerceAtMost(MAX_FAILURE_EXPONENT + 1)
        val exponent = (state.consecutiveFailures - 1).coerceIn(0, MAX_FAILURE_EXPONENT)
        val multiplier = 1L shl exponent
        val delayMillis = safeMultiply(policy.failureBackoffBaseMillis, multiplier)
            .coerceAtMost(policy.failureBackoffMaxMillis)
        state.retryNotBeforeMillis = safeAdd(nowMillis, delayMillis)
    }

    private fun clearManagedPeerLocked(peerId: Long, state: CandidateState) {
        if (state.managedPeerId == peerId) state.managedPeerId = null
        state.startInProgress = false
        state.startReserved = false
        peerIdToEndpointKey.remove(peerId)
    }

    private fun stateForPeerLocked(peerId: Long): CandidateState? {
        val key = peerIdToEndpointKey[peerId] ?: return null
        return candidates[key]
    }

    private fun endpointKey(endpoint: AceLiveTcpPeerEndpoint): String =
        "${endpoint.host}:${endpoint.port}"

    private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
        if (nowMillis >= startMillis) nowMillis - startMillis else Long.MAX_VALUE

    private fun safeMultiply(left: Long, right: Long): Long =
        if (left == 0L || right <= Long.MAX_VALUE / left) left * right else Long.MAX_VALUE

    private fun safeAdd(left: Long, right: Long): Long =
        if (right <= Long.MAX_VALUE - left) left + right else Long.MAX_VALUE

    private fun <T> withStateLock(block: () -> T): T = synchronized(stateLock) { block() }

    private data class CandidateState(
        val endpoint: AceLiveTcpPeerEndpoint,
        val sources: MutableSet<AceLivePeerDiscoverySource> = linkedSetOf(),
        var advertisedWindow: AceLivePeerAdvertisedWindow? = null,
        var consecutiveFailures: Int = 0,
        var retryNotBeforeMillis: Long = 0,
        var lastDiscoveredAtMillis: Long = 0,
        var lastHandshakeAtMillis: Long? = null,
        var managedPeerId: Long? = null,
        var startReserved: Boolean = false,
        var startInProgress: Boolean = false
    ) {
        fun toPublicCandidate(): AceLivePeerRefillCandidate = AceLivePeerRefillCandidate(
            endpoint = endpoint,
            sources = sources.toSet(),
            advertisedWindow = advertisedWindow,
            consecutiveFailures = consecutiveFailures,
            hasSuccessfulHandshake = lastHandshakeAtMillis != null
        )

        fun toSnapshot(): AceLivePeerRefillSnapshot = AceLivePeerRefillSnapshot(
            endpoint = endpoint,
            sources = sources.toSet(),
            advertisedWindow = advertisedWindow,
            consecutiveFailures = consecutiveFailures,
            retryNotBeforeMillis = retryNotBeforeMillis,
            lastDiscoveredAtMillis = lastDiscoveredAtMillis,
            lastHandshakeAtMillis = lastHandshakeAtMillis,
            managedPeerId = managedPeerId,
            startReserved = startReserved,
            startInProgress = startInProgress
        )
    }

    private companion object {
        const val MAX_FAILURE_EXPONENT: Int = 20
    }
}

/**
 * Cancellable background refill loop over injected discovery/pool boundaries.
 *
 * The loop performs discovery only when the pool is below its normal target or recovery reports a
 * stale-but-reachable pool. It does not stop peers when the pool later becomes healthy; connection
 * teardown remains owned by the TCP pool/caller rather than by a scoring heuristic.
 */
class AceLivePeerRefillLoop(
    private val coordinator: AceLivePeerRefillCoordinator,
    private val discover: suspend () -> AceLivePeerDiscoveryOrchestrationResult,
    private val activePeerIds: suspend () -> Set<Long>,
    private val evaluateRecovery: suspend () -> AceLiveRecoveryPlan,
    private val nextNeededPiece: suspend () -> Long?,
    private val allocatePeerId: () -> Long,
    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    suspend fun runOneCycle(nowMillis: Long = clockMillis()): AceLivePeerRefillCycleResult {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        val active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        val recovery = evaluateRecovery()
        val needsDiscovery = active.size < coordinator.policy.targetActivePeers || recovery.poolStale
        if (!needsDiscovery) {
            return AceLivePeerRefillCycleResult(
                discoveryAttempted = false,
                plannedStarts = 0,
                startedPeers = 0,
                immediateStartFailures = 0,
                poolStale = recovery.poolStale
            )
        }

        val discovery = discover()
        coordinator.ingestDiscovery(discovery, nowMillis)
        val plan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = nextNeededPiece(),
            poolStale = recovery.poolStale,
            nowMillis = nowMillis
        )

        var started = 0
        var failed = 0
        val unstartedReservations = plan.candidates
            .mapTo(linkedSetOf()) { candidate -> candidate.endpoint }
        try {
            for (candidate in plan.candidates) {
                currentCoroutineContext().ensureActive()
                val peerId = allocatePeerId()
                require(peerId >= 0) { "allocatePeerId returned a negative peer id" }
                coordinator.beginStart(peerId, candidate.endpoint)
                unstartedReservations.remove(candidate.endpoint)
                try {
                    startPeer(peerId, candidate.endpoint)
                    coordinator.markStartAccepted(peerId)
                    started += 1
                } catch (cancelled: CancellationException) {
                    coordinator.cancelStart(peerId)
                    throw cancelled
                } catch (_: Throwable) {
                    coordinator.markStartRejected(peerId, clockMillis())
                    failed += 1
                }
            }
        } finally {
            unstartedReservations.forEach { endpoint ->
                coordinator.releaseReservation(endpoint)
            }
        }

        return AceLivePeerRefillCycleResult(
            discoveryAttempted = true,
            plannedStarts = plan.candidates.size,
            startedPeers = started,
            immediateStartFailures = failed,
            poolStale = recovery.poolStale
        )
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            runOneCycle()
            delay(coordinator.policy.refreshIntervalMillis)
        }
    }
}
