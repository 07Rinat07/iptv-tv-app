package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

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
    val hasSuccessfulHandshake: Boolean,
    val peerExchangeSourceCount: Int = 0
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
    val startInProgress: Boolean,
    val peerExchangeSourceCount: Int = 0
)

data class AceLivePeerRefillCycleResult(
    val discoveryAttempted: Boolean,
    val plannedStarts: Int,
    val startedPeers: Int,
    val immediateStartFailures: Int,
    val poolStale: Boolean,
    val replacedPeerId: Long? = null
)

internal fun aceLivePeerRefillEventShouldWake(event: AceLiveTcpPoolEvent): Boolean = when (event) {
    is AceLiveTcpPoolEvent.Ingress -> event.result.peerExchangePeers.isNotEmpty()
    is AceLiveTcpPoolEvent.ConnectFailed -> !event.retrying
    is AceLiveTcpPoolEvent.Disconnected -> !event.retrying
    is AceLiveTcpPoolEvent.TransportConnected,
    is AceLiveTcpPoolEvent.HandshakeAccepted,
    is AceLiveTcpPoolEvent.HandshakeRejected -> false
}

/**
 * Candidate state/ranking for background Ace Live peer refill.
 *
 * Ranking gives verified live-window usefulness precedence, then uses short-lived same-swarm
 * reputation from earlier runtimes before local failure/handshake/source tie-breakers. Persisted
 * evidence never creates a permanent allow/deny: discovery still owns the candidate set, while
 * local retry state and shared same-swarm transport backoff both gate immediate eligibility.
 * Equal-quality refill candidates are spread across independent discovery classes when possible;
 * source diversity never overrides verified window/reputation/failure/handshake quality evidence.
 *
 * All mutable candidate/reservation/ownership state is serialized under one local monitor. Disk
 * persistence is deliberately invoked only after leaving this monitor so a filesystem stall cannot
 * block pool ownership/refill decisions.
 *
 * Owned pool entries and protocol-qualified peers are deliberately separate. A started socket/job
 * consumes hard capacity immediately, but it does not satisfy the normal peer target until the
 * current connection has completed its handshake. This prevents dead or incompatible endpoints
 * from suppressing alternative tracker/DHT discovery while retaining the existing socket hard cap.
 */
class AceLivePeerRefillCoordinator(
    val policy: AceLivePeerRefillPolicy = AceLivePeerRefillPolicy(),
    swarmKey: ByteArray? = null,
    private val reputationStore: AceLivePeerReputationStore? = null,
    private val connectFailureMemory: AceLiveTcpConnectFailureMemory =
        AceLiveTcpConnectFailureMemory.shared
) {
    private val transportSwarmKey = swarmKey?.copyOf()
    private val stateLock = Any()
    private val candidates = LinkedHashMap<String, CandidateState>()
    private val peerIdToEndpointKey = HashMap<Long, String>()

    init {
        require(reputationStore == null || transportSwarmKey != null) {
            "swarmKey is required when reputationStore is supplied"
        }
        transportSwarmKey?.let { key ->
            require(key.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
                "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
            }
        }
    }

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
     * Current protocol-qualified peer count among pool-owned ids.
     *
     * Runtime peers created by this coordinator carry exact handshake evidence. Unknown active ids
     * are treated optimistically for compatibility with injected/test callers that do not expose
     * lifecycle events; production refill-managed peers are never unknown after [beginStart].
     */
    fun qualifiedActivePeerCount(activePeerIds: Set<Long>): Int = withStateLock {
        qualifiedActivePeerCountLocked(activePeerIds)
    }

    /**
     * Returns the bounded number of starts still useful for the current qualification/capacity
     * state without reserving a candidate. This is a start-capacity query, not a discovery-demand
     * query: discovery may still refresh candidates while hard socket capacity is temporarily full.
     */
    fun refillDemandSlots(
        activePeerIds: Set<Long>,
        poolStale: Boolean,
        nowMillis: Long,
        extraProbePeers: Int = 0,
        maxStarts: Int = policy.maxStartsPerCycle
    ): Int = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        require(extraProbePeers >= 0) { "extraProbePeers must be non-negative" }
        require(maxStarts >= 0) { "maxStarts must be non-negative" }
        syncActivePeerIdsLocked(activePeerIds)
        pruneExpiredCandidatesLocked(nowMillis)
        refillDemandSlotsLocked(
            activePeerIds = activePeerIds,
            poolStale = poolStale,
            extraProbePeers = extraProbePeers,
            maxStarts = maxStarts
        )
    }

    /**
     * Produces and reserves a bounded set of candidates. Selection and reservation occur under the
     * same lock, so overlapping cycles cannot reserve the same endpoint.
     *
     * Qualification demand and ownership capacity are intentionally calculated independently:
     * unhandshaked active jobs do not satisfy the qualified target, while every active/reserved/
     * not-yet-visible managed start still consumes the hard [AceLivePeerRefillPolicy.maxActivePeers]
     * capacity. This allows alternative peers to be tried without ever planning an 11th socket when
     * the configured hard cap is 10.
     */
    fun planRefill(
        activePeerIds: Set<Long>,
        nextNeededPiece: Long?,
        poolStale: Boolean,
        nowMillis: Long,
        extraProbePeers: Int = 0,
        maxStarts: Int = policy.maxStartsPerCycle
    ): AceLivePeerRefillPlan = withStateLock {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        require(nextNeededPiece == null || nextNeededPiece >= 0) {
            "nextNeededPiece must be non-negative when present"
        }
        require(extraProbePeers >= 0) { "extraProbePeers must be non-negative" }
        require(maxStarts >= 0) { "maxStarts must be non-negative" }
        syncActivePeerIdsLocked(activePeerIds)
        pruneExpiredCandidatesLocked(nowMillis)

        val desired = desiredActivePeersLocked(poolStale, extraProbePeers)
        val slots = refillDemandSlotsLocked(
            activePeerIds = activePeerIds,
            poolStale = poolStale,
            extraProbePeers = extraProbePeers,
            maxStarts = maxStarts
        )
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
            .filter { state -> transportEligible(state.endpoint, nowMillis) }
            .sortedWith(candidateComparator(nextNeededPiece, nowMillis))
            .toList()
        val selected = selectPlanCandidatesLocked(
            ranked = ranked,
            slots = slots,
            nextNeededPiece = nextNeededPiece,
            nowMillis = nowMillis
        )

        selected.forEach { state -> state.startReserved = true }
        AceLivePeerRefillPlan(
            candidates = selected.map(CandidateState::toPublicCandidate),
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
        state.currentlyQualified = false
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
     * Pool events update local evidence under the coordinator lock and persist cross-runtime
     * reputation afterwards. Retry-internal failures are not persisted until the TCP pool exhausts
     * its own retry loop.
     */
    fun onPoolEvent(event: AceLiveTcpPoolEvent, nowMillis: Long) {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        val evidence = withStateLock {
            if (event is AceLiveTcpPoolEvent.Ingress && event.result.peerExchangePeers.isNotEmpty()) {
                ingestPeerExchangeLocked(
                    sourcePeerId = event.peerId,
                    peers = event.result.peerExchangePeers,
                    nowMillis = nowMillis
                )
            }
            val state = stateForPeerLocked(event.peerId) ?: return@withStateLock null
            when (event) {
                is AceLiveTcpPoolEvent.TransportConnected -> {
                    state.startInProgress = false
                    state.currentlyQualified = false
                    null
                }

                is AceLiveTcpPoolEvent.HandshakeAccepted -> {
                    state.startInProgress = false
                    state.currentlyQualified = true
                    state.consecutiveFailures = 0
                    state.retryNotBeforeMillis = 0
                    state.lastHandshakeAtMillis = nowMillis
                    ReputationEvidence(state.endpoint, ReputationEvidenceType.HANDSHAKE_ACCEPTED)
                }

                is AceLiveTcpPoolEvent.Ingress -> {
                    event.result.metadataUpdates.lastOrNull()?.let { window ->
                        state.advertisedWindow = window
                    }
                    null
                }

                is AceLiveTcpPoolEvent.ConnectFailed -> {
                    state.currentlyQualified = false
                    if (!event.retrying) {
                        val result = ReputationEvidence(
                            state.endpoint,
                            ReputationEvidenceType.FINAL_FAILURE
                        )
                        recordFailureLocked(state, nowMillis)
                        clearManagedPeerLocked(event.peerId, state)
                        result
                    } else {
                        null
                    }
                }

                is AceLiveTcpPoolEvent.Disconnected -> {
                    state.currentlyQualified = false
                    if (!event.retrying) {
                        val result = ReputationEvidence(
                            state.endpoint,
                            ReputationEvidenceType.FINAL_FAILURE
                        )
                        recordFailureLocked(state, nowMillis)
                        clearManagedPeerLocked(event.peerId, state)
                        result
                    } else {
                        null
                    }
                }

                is AceLiveTcpPoolEvent.HandshakeRejected -> {
                    state.currentlyQualified = false
                    ReputationEvidence(state.endpoint, ReputationEvidenceType.FINAL_FAILURE)
                }
            }
        }
        recordReputationEvidence(evidence, nowMillis)
    }

    /** Called only after authenticated/resynchronized bytes were actually accepted by media output. */
    fun markMediaProduced(peerId: Long, nowMillis: Long) {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        val endpoint = withStateLock { stateForPeerLocked(peerId)?.endpoint } ?: return
        val key = transportSwarmKey ?: return
        reputationStore?.recordMediaProduced(key, endpoint, nowMillis)
    }

    fun snapshot(endpoint: AceLiveTcpPeerEndpoint): AceLivePeerRefillSnapshot? = withStateLock {
        candidates[endpointKey(endpoint)]?.toSnapshot()
    }

    fun snapshots(): List<AceLivePeerRefillSnapshot> = withStateLock {
        candidates.values.map(CandidateState::toSnapshot)
    }

    private fun desiredActivePeersLocked(poolStale: Boolean, extraProbePeers: Int): Int {
        val recoveryProbePeers = if (poolStale) policy.staleProbePeers else 0
        val requestedProbePeers = maxOf(recoveryProbePeers, extraProbePeers)
            .coerceAtMost(policy.maxActivePeers)
        return (policy.targetActivePeers + requestedProbePeers)
            .coerceAtMost(policy.maxActivePeers)
    }

    private fun refillDemandSlotsLocked(
        activePeerIds: Set<Long>,
        poolStale: Boolean,
        extraProbePeers: Int,
        maxStarts: Int
    ): Int {
        if (maxStarts == 0) return 0
        val desired = desiredActivePeersLocked(poolStale, extraProbePeers)
        val reservedPending = candidates.values.count { state -> state.startReserved }
        val managedPending = peerIdToEndpointKey.keys.count { peerId -> peerId !in activePeerIds }
        val qualifiedActivePeers = qualifiedActivePeerCountLocked(activePeerIds)
        val qualificationCommittedPeers = qualifiedActivePeers + reservedPending + managedPending
        val ownedCommittedPeers = activePeerIds.size + reservedPending + managedPending
        val qualificationSlots = (desired - qualificationCommittedPeers).coerceAtLeast(0)
        val hardCapacitySlots = (policy.maxActivePeers - ownedCommittedPeers).coerceAtLeast(0)
        return minOf(qualificationSlots, hardCapacitySlots, maxStarts)
    }

    private fun qualifiedActivePeerCountLocked(activePeerIds: Set<Long>): Int =
        activePeerIds.count { peerId ->
            stateForPeerLocked(peerId)?.currentlyQualified ?: true
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
            if (state.managedPeerId == peerId) {
                state.managedPeerId = null
                state.currentlyQualified = false
            }
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

    private fun candidateComparator(
        nextNeededPiece: Long?,
        nowMillis: Long
    ): Comparator<CandidateState> = Comparator { left, right ->
        compareValuesBy(
            left,
            right,
            { state -> windowUsefulnessRank(state.advertisedWindow, nextNeededPiece) },
            { state -> persistentReputationRank(state.endpoint, nowMillis) },
            { state -> state.consecutiveFailures },
            { state -> if (state.lastHandshakeAtMillis != null) 0 else 1 },
            { state -> -state.discoverySourceCount() },
            { state -> -state.lastDiscoveredAtMillis },
            { state -> state.endpoint.host },
            { state -> state.endpoint.port }
        )
    }

    private fun selectPlanCandidatesLocked(
        ranked: List<CandidateState>,
        slots: Int,
        nextNeededPiece: Long?,
        nowMillis: Long
    ): List<CandidateState> {
        if (slots <= 0) return emptyList()

        val committedStates = candidates.values.filter { state ->
            state.managedPeerId != null || state.startReserved || state.startInProgress
        }
        val usedDiscoveryClasses = committedStates
            .flatMapTo(linkedSetOf()) { state -> state.discoveryClasses() }
        val usedSinglePexSources = committedStates
            .asSequence()
            .filter(CandidateState::isPeerExchangeOnly)
            .flatMap { state -> state.peerExchangeSourcePeerIds.asSequence() }
            .toMutableSet()
        val pexSourceFrequency = ranked
            .asSequence()
            .filter(CandidateState::isPeerExchangeOnly)
            .flatMap { state -> state.peerExchangeSourcePeerIds.asSequence() }
            .groupingBy { sourcePeerId -> sourcePeerId }
            .eachCount()

        val remaining = ranked.toMutableList()
        val selected = ArrayList<CandidateState>(slots)
        while (selected.size < slots && remaining.isNotEmpty()) {
            val eligible = remaining.filter { state ->
                !state.isPeerExchangeOnly() ||
                    state.peerExchangeSourcePeerIds.any { sourcePeerId ->
                        sourcePeerId !in usedSinglePexSources
                    }
            }
            if (eligible.isEmpty()) break

            val bestQualityTier = candidateQualityTier(
                state = eligible.first(),
                nextNeededPiece = nextNeededPiece,
                nowMillis = nowMillis
            )
            val sameQualityTier = eligible.takeWhile { state ->
                candidateQualityTier(state, nextNeededPiece, nowMillis) == bestQualityTier
            }
            val chosen = sameQualityTier.firstOrNull { state ->
                state.discoveryClasses().any { sourceClass ->
                    sourceClass !in usedDiscoveryClasses
                }
            } ?: sameQualityTier.first()

            if (chosen.isPeerExchangeOnly()) {
                val independentSource = chosen.peerExchangeSourcePeerIds
                    .asSequence()
                    .filter { sourcePeerId -> sourcePeerId !in usedSinglePexSources }
                    .minByOrNull { sourcePeerId -> pexSourceFrequency[sourcePeerId] ?: Int.MAX_VALUE }
                    ?: error("Eligible PEX candidate lost its independent source")
                usedSinglePexSources += independentSource
            }
            usedDiscoveryClasses += chosen.discoveryClasses()
            selected += chosen
            remaining.remove(chosen)
        }
        return selected
    }

    private fun candidateQualityTier(
        state: CandidateState,
        nextNeededPiece: Long?,
        nowMillis: Long
    ): CandidateQualityTier = CandidateQualityTier(
        windowUsefulnessRank = windowUsefulnessRank(state.advertisedWindow, nextNeededPiece),
        persistentReputationRank = persistentReputationRank(state.endpoint, nowMillis),
        consecutiveFailures = state.consecutiveFailures,
        handshakeRank = if (state.lastHandshakeAtMillis != null) 0 else 1
    )

    private fun ingestPeerExchangeLocked(
        sourcePeerId: Long,
        peers: List<AceLiveTcpPeerEndpoint>,
        nowMillis: Long
    ) {
        pruneExpiredCandidatesLocked(nowMillis)
        val sourceEndpoint = stateForPeerLocked(sourcePeerId)?.endpoint
        for (endpoint in peers.take(MAX_PEX_PEERS_PER_INGRESS)) {
            if (sourceEndpoint != null && endpoint.host == sourceEndpoint.host) continue
            val key = endpointKey(endpoint)
            var state = candidates[key]
            if (state == null) {
                val pexOnlyCount = candidates.values.count { candidate ->
                    candidate.sources.isEmpty() && candidate.peerExchangeSourcePeerIds.isNotEmpty()
                }
                if (pexOnlyCount >= MAX_PEX_ONLY_CANDIDATES) continue
                state = CandidateState(endpoint = endpoint)
                candidates[key] = state
            }
            state.peerExchangeSourcePeerIds += sourcePeerId
            state.lastDiscoveredAtMillis = nowMillis
        }
    }

    private fun transportEligible(
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ): Boolean {
        val key = transportSwarmKey ?: return true
        return connectFailureMemory.isEligible(key, endpoint, nowMillis)
    }

    private fun persistentReputationRank(
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ): Int {
        val key = transportSwarmKey ?: return NEUTRAL_REPUTATION_RANK
        return reputationStore
            ?.snapshot(key, endpoint, nowMillis)
            ?.priorityRank(nowMillis)
            ?: NEUTRAL_REPUTATION_RANK
    }

    private fun recordReputationEvidence(
        evidence: ReputationEvidence?,
        nowMillis: Long
    ) {
        val item = evidence ?: return
        val key = transportSwarmKey ?: return
        val store = reputationStore ?: return
        when (item.type) {
            ReputationEvidenceType.HANDSHAKE_ACCEPTED ->
                store.recordHandshakeAccepted(key, item.endpoint, nowMillis)
            ReputationEvidenceType.FINAL_FAILURE ->
                store.recordFinalFailure(key, item.endpoint, nowMillis)
        }
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
        state.currentlyQualified = false
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

    private data class CandidateQualityTier(
        val windowUsefulnessRank: Int,
        val persistentReputationRank: Int,
        val consecutiveFailures: Int,
        val handshakeRank: Int
    )

    private enum class CandidateDiscoveryClass {
        MAINLINE_DHT,
        UDP_TRACKER,
        LOCAL_SERVICE_DISCOVERY,
        PEER_EXCHANGE
    }

    private data class CandidateState(
        val endpoint: AceLiveTcpPeerEndpoint,
        val sources: MutableSet<AceLivePeerDiscoverySource> = linkedSetOf(),
        val peerExchangeSourcePeerIds: MutableSet<Long> = linkedSetOf(),
        var advertisedWindow: AceLivePeerAdvertisedWindow? = null,
        var consecutiveFailures: Int = 0,
        var retryNotBeforeMillis: Long = 0,
        var lastDiscoveredAtMillis: Long = 0,
        var lastHandshakeAtMillis: Long? = null,
        var managedPeerId: Long? = null,
        var startReserved: Boolean = false,
        var startInProgress: Boolean = false,
        var currentlyQualified: Boolean = false
    ) {
        fun toPublicCandidate(): AceLivePeerRefillCandidate = AceLivePeerRefillCandidate(
            endpoint = endpoint,
            sources = sources.toSet(),
            advertisedWindow = advertisedWindow,
            consecutiveFailures = consecutiveFailures,
            hasSuccessfulHandshake = lastHandshakeAtMillis != null,
            peerExchangeSourceCount = peerExchangeSourcePeerIds.size
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
            startInProgress = startInProgress,
            peerExchangeSourceCount = peerExchangeSourcePeerIds.size
        )

        fun discoverySourceCount(): Int =
            sources.size + if (peerExchangeSourcePeerIds.isEmpty()) 0 else 1

        fun isPeerExchangeOnly(): Boolean =
            sources.isEmpty() && peerExchangeSourcePeerIds.isNotEmpty()

        fun discoveryClasses(): Set<CandidateDiscoveryClass> {
            val result = linkedSetOf<CandidateDiscoveryClass>()
            for (source in sources) {
                result += when (source) {
                    AceLivePeerDiscoverySource.MAINLINE_DHT ->
                        CandidateDiscoveryClass.MAINLINE_DHT
                    AceLivePeerDiscoverySource.UDP_TRACKER ->
                        CandidateDiscoveryClass.UDP_TRACKER
                    AceLivePeerDiscoverySource.LOCAL_SERVICE_DISCOVERY ->
                        CandidateDiscoveryClass.LOCAL_SERVICE_DISCOVERY
                }
            }
            if (peerExchangeSourcePeerIds.isNotEmpty()) {
                result += CandidateDiscoveryClass.PEER_EXCHANGE
            }
            return result
        }
    }

    private data class ReputationEvidence(
        val endpoint: AceLiveTcpPeerEndpoint,
        val type: ReputationEvidenceType
    )

    private enum class ReputationEvidenceType {
        HANDSHAKE_ACCEPTED,
        FINAL_FAILURE
    }

    private companion object {
        const val MAX_FAILURE_EXPONENT: Int = 20
        const val NEUTRAL_REPUTATION_RANK: Int = 2
        const val MAX_PEX_PEERS_PER_INGRESS: Int = AceLivePeerExchangeCodec.MAX_ADDED_IPV4_PEERS
        const val MAX_PEX_ONLY_CANDIDATES: Int = 128
    }
}

/**
 * Cancellable background refill loop over injected discovery/pool boundaries.
 *
 * Already-known candidates are attempted before tracker/DHT work. Network discovery is a bounded
 * fallback for qualification demand that remains after those starts, rather than a mandatory gate
 * in front of PEX/recent candidates. Event-driven wakeups are conflated so PEX/final-failure bursts
 * can interrupt the periodic wait without creating parallel refill/discovery cycles. A hard-full
 * socket pool may still refresh discovery while it waits for capacity; it simply cannot allocate
 * another socket. Ownership remains the hard-cap signal inside
 * [AceLivePeerRefillCoordinator.planRefill].
 */
class AceLivePeerRefillLoop(
    private val coordinator: AceLivePeerRefillCoordinator,
    private val discover: suspend () -> AceLivePeerDiscoveryOrchestrationResult,
    private val activePeerIds: suspend () -> Set<Long>,
    private val evaluateRecovery: suspend () -> AceLiveRecoveryPlan,
    private val nextNeededPiece: suspend () -> Long?,
    private val allocatePeerId: () -> Long,
    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val adaptiveProbePeers: suspend () -> Int = { 0 },
    private val replacementPeerId: suspend (activePeerIds: Set<Long>, nowMillis: Long) -> Long? =
        { _, _ -> null },
    private val stopPeer: suspend (peerId: Long) -> Unit = {}
) {
    private val cycleMutex = Mutex()
    private val wakeups = Channel<Unit>(capacity = Channel.CONFLATED)

    fun requestWakeup() {
        wakeups.trySend(Unit)
    }

    suspend fun runOneCycle(): AceLivePeerRefillCycleResult = cycleMutex.withLock {
        runOneCycleSerialized(clockMillis())
    }

    suspend fun runOneCycle(nowMillis: Long): AceLivePeerRefillCycleResult = cycleMutex.withLock {
        runOneCycleSerialized(nowMillis)
    }

    private suspend fun runOneCycleSerialized(nowMillis: Long): AceLivePeerRefillCycleResult {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
        var active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        var replacedPeerId: Long? = null
        val selectedForReplacement = replacementPeerId(active, nowMillis)
            ?.takeIf { peerId -> peerId in active }
        if (selectedForReplacement != null) {
            try {
                stopPeer(selectedForReplacement)
                replacedPeerId = selectedForReplacement
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Replacement is an optimization. A failed explicit stop must not fail playback.
            }
            active = activePeerIds()
            if (selectedForReplacement !in active) {
                replacedPeerId = selectedForReplacement
            }
            coordinator.syncActivePeerIds(active)
        }

        val recovery = evaluateRecovery()
        val requestedAdaptiveProbePeers = adaptiveProbePeers().coerceAtLeast(0)
        val recoveryProbePeers = if (recovery.poolStale) coordinator.policy.staleProbePeers else 0
        val requestedProbePeers = maxOf(recoveryProbePeers, requestedAdaptiveProbePeers)
            .coerceAtMost(coordinator.policy.maxActivePeers)
        val desiredQualifiedPeers = (coordinator.policy.targetActivePeers + requestedProbePeers)
            .coerceAtMost(coordinator.policy.maxActivePeers)
        fun hasQualificationDemand(peerIds: Set<Long>): Boolean =
            coordinator.qualifiedActivePeerCount(peerIds) < desiredQualifiedPeers

        if (!hasQualificationDemand(active)) {
            return AceLivePeerRefillCycleResult(
                discoveryAttempted = false,
                plannedStarts = 0,
                startedPeers = 0,
                immediateStartFailures = 0,
                poolStale = recovery.poolStale,
                replacedPeerId = replacedPeerId
            )
        }

        var remainingStartBudget = coordinator.policy.maxStartsPerCycle
        var plannedStarts = 0
        var startedPeers = 0
        var immediateStartFailures = 0

        val knownPlan = coordinator.planRefill(
            activePeerIds = active,
            nextNeededPiece = nextNeededPiece(),
            poolStale = recovery.poolStale,
            nowMillis = nowMillis,
            extraProbePeers = requestedAdaptiveProbePeers,
            maxStarts = remainingStartBudget
        )
        val knownResult = startPlan(knownPlan, desiredQualifiedPeers)
        plannedStarts += knownResult.plannedStarts
        startedPeers += knownResult.startedPeers
        immediateStartFailures += knownResult.immediateStartFailures
        remainingStartBudget -= knownResult.plannedStarts

        active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        val discoveryNeeded = remainingStartBudget > 0 && hasQualificationDemand(active)

        if (discoveryNeeded) {
            val discovery = discover()
            coordinator.ingestDiscovery(discovery, nowMillis)
            if (remainingStartBudget > 0) {
                active = activePeerIds()
                val discoveredPlan = coordinator.planRefill(
                    activePeerIds = active,
                    nextNeededPiece = nextNeededPiece(),
                    poolStale = recovery.poolStale,
                    nowMillis = nowMillis,
                    extraProbePeers = requestedAdaptiveProbePeers,
                    maxStarts = remainingStartBudget
                )
                val discoveredResult = startPlan(discoveredPlan, desiredQualifiedPeers)
                plannedStarts += discoveredResult.plannedStarts
                startedPeers += discoveredResult.startedPeers
                immediateStartFailures += discoveredResult.immediateStartFailures
            }
        }

        return AceLivePeerRefillCycleResult(
            discoveryAttempted = discoveryNeeded,
            plannedStarts = plannedStarts,
            startedPeers = startedPeers,
            immediateStartFailures = immediateStartFailures,
            poolStale = recovery.poolStale,
            replacedPeerId = replacedPeerId
        )
    }

    suspend fun run() {
        while (currentCoroutineContext().isActive) {
            runOneCycle()
            withTimeoutOrNull(coordinator.policy.refreshIntervalMillis) {
                wakeups.receive()
            }
        }
    }

    private suspend fun startPlan(
        plan: AceLivePeerRefillPlan,
        desiredQualifiedPeers: Int
    ): StartPlanResult {
        var planned = 0
        var started = 0
        var failed = 0
        val unstartedReservations = plan.candidates
            .mapTo(linkedSetOf()) { candidate -> candidate.endpoint }
        try {
            for (candidate in plan.candidates) {
                currentCoroutineContext().ensureActive()
                val active = activePeerIds()
                coordinator.syncActivePeerIds(active)
                if (coordinator.qualifiedActivePeerCount(active) >= desiredQualifiedPeers) break

                planned += 1
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
        return StartPlanResult(
            plannedStarts = planned,
            startedPeers = started,
            immediateStartFailures = failed
        )
    }

    private data class StartPlanResult(
        val plannedStarts: Int,
        val startedPeers: Int,
        val immediateStartFailures: Int
    )
}
