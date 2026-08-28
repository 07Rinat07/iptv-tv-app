package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Public discovery source identity kept with a peer for later scoring/refill policy. */
enum class AceLivePeerDiscoverySource {
    MAINLINE_DHT,
    UDP_TRACKER
}

enum class AceLivePeerDiscoverySourceStatus {
    NOT_REQUESTED,
    SUCCEEDED,
    FAILED
}

data class AceLivePeerDiscoverySourceSummary(
    val status: AceLivePeerDiscoverySourceStatus,
    val returnedPeerCount: Int
) {
    init {
        require(returnedPeerCount >= 0) { "returnedPeerCount must be non-negative" }
        if (status != AceLivePeerDiscoverySourceStatus.SUCCEEDED) {
            require(returnedPeerCount == 0) {
                "Non-successful discovery source cannot report returned peers"
            }
        }
    }
}

data class AceLiveDiscoveredPeer(
    val endpoint: AceLiveTcpPeerEndpoint,
    val sources: Set<AceLivePeerDiscoverySource>
) {
    init {
        require(sources.isNotEmpty()) { "At least one discovery source is required" }
    }
}

/**
 * Orchestration request made only from already-validated source-specific requests.
 *
 * A tracker request remains optional because BEP-15 announce requires a real caller-owned peer
 * listener port. This boundary never manufactures an announce port or substitutes an HTTP API port.
 */
class AceLivePeerDiscoveryOrchestrationRequest(
    val dhtRequest: AceLiveDhtDiscoveryRequest? = null,
    val trackerRequest: AceLiveUdpTrackerDiscoveryRequest? = null
) {
    init {
        require(dhtRequest != null || trackerRequest != null) {
            "At least one peer discovery source must be requested"
        }
        if (dhtRequest != null && trackerRequest != null) {
            require(dhtRequest.swarmKey == trackerRequest.swarmKey) {
                "DHT and tracker discovery must target the same Ace Live swarm key"
            }
        }
    }
}

data class AceLivePeerDiscoveryOrchestrationPolicy(
    val maxTotalPeers: Int = 256,
    val preferTrackerFastPath: Boolean = true,
    val trackerFastPathMinPeers: Int = 4
) {
    init {
        require(maxTotalPeers in 1..2_048) { "maxTotalPeers must be in 1..2048" }
        require(trackerFastPathMinPeers in 1..2_048) {
            "trackerFastPathMinPeers must be in 1..2048"
        }
    }
}

data class AceLivePeerDiscoveryOrchestrationResult(
    val peers: List<AceLiveDiscoveredPeer>,
    val dht: AceLivePeerDiscoverySourceSummary,
    val tracker: AceLivePeerDiscoverySourceSummary,
    val dhtQueriesSent: Int = 0,
    val dhtFailedQueries: Int = 0,
    val dhtWarmRoutingSeedsUsed: Int = 0,
    val dhtCacheHit: Boolean = false,
    val dhtAnnouncesSent: Int = 0,
    val dhtAnnouncesSucceeded: Int = 0
) {
    init {
        require(dhtQueriesSent >= 0) { "DHT query count must be non-negative" }
        require(dhtFailedQueries >= 0) { "DHT failed query count must be non-negative" }
        require(dhtWarmRoutingSeedsUsed >= 0) { "DHT warm seed count must be non-negative" }
        require(dhtAnnouncesSent >= 0) { "DHT announce count must be non-negative" }
        require(dhtAnnouncesSucceeded in 0..dhtAnnouncesSent) {
            "DHT successful announce count must not exceed sent announces"
        }
    }

    fun tcpEndpoints(): List<AceLiveTcpPeerEndpoint> = peers.map(AceLiveDiscoveredPeer::endpoint)
}

/** Startup-only DHT work required after the deliberately permissive initial discovery. */
internal enum class AceLiveStartupDhtRefillPlan {
    NONE,
    PROBE_BATCHES_THEN_EXPAND
}

/**
 * Classifies the DHT-only work required after the deliberately permissive tracker-first startup.
 *
 * Tracker count is discovery evidence, not qualification evidence. A tracker-only result can contain
 * several endpoints that all fail TCP/Ace qualification, as the R3 TV Box run demonstrated. The
 * tracker fast path must therefore remain non-blocking for the first TCP attempts while scheduling
 * bounded DHT diversity work in the background until startup reaches media-ready. A short probe uses
 * the existing seven-second startup-DHT window to collect a small batch of independent alternatives;
 * a second bounded probe may explore an independent routing-table path, and the existing full
 * expansion remains the wider routing-table path without widening the per-round time/query budgets.
 *
 * A weak DHT fast path (one to three eligible endpoints) uses the same bounded probe/expansion
 * sequence. Raw source counts remain diagnostics and do not classify post-filter startup strength.
 */
internal fun aceLiveStartupNeedsImmediateDhtOnlyRefill(
    result: AceLivePeerDiscoveryOrchestrationResult,
    normalTrackerFastPathMinPeers: Int =
        AceLivePeerDiscoveryOrchestrationPolicy().trackerFastPathMinPeers
): Boolean {
    return aceLiveStartupDhtRefillPlan(result, normalTrackerFastPathMinPeers) !=
        AceLiveStartupDhtRefillPlan.NONE
}

internal fun aceLiveStartupDhtRefillPlan(
    result: AceLivePeerDiscoveryOrchestrationResult,
    normalTrackerFastPathMinPeers: Int =
        AceLivePeerDiscoveryOrchestrationPolicy().trackerFastPathMinPeers
): AceLiveStartupDhtRefillPlan {
    require(normalTrackerFastPathMinPeers > 0) {
        "normalTrackerFastPathMinPeers must be positive"
    }
    val eligibleTrackerPeers = result.peers.count { peer ->
        AceLivePeerDiscoverySource.UDP_TRACKER in peer.sources
    }
    val eligibleDhtPeers = result.peers.count { peer ->
        AceLivePeerDiscoverySource.MAINLINE_DHT in peer.sources
    }
    val trackerOnlyFastPath =
        result.dht.status == AceLivePeerDiscoverySourceStatus.NOT_REQUESTED &&
        result.tracker.status == AceLivePeerDiscoverySourceStatus.SUCCEEDED &&
        eligibleTrackerPeers > 0
    val weakDhtFastPath =
        result.dht.status == AceLivePeerDiscoverySourceStatus.SUCCEEDED &&
        eligibleDhtPeers in 1 until normalTrackerFastPathMinPeers
    return when {
        // Tracker endpoints are only candidates. Keep their immediate startup advantage, but obtain
        // independent DHT alternatives in the background in case tracker peers cannot qualify.
        trackerOnlyFastPath || weakDhtFastPath ->
            AceLiveStartupDhtRefillPlan.PROBE_BATCHES_THEN_EXPAND
        else -> AceLiveStartupDhtRefillPlan.NONE
    }
}

internal fun aceLiveStartupDhtProbeShouldContinue(
    completedRounds: Int,
    maxRounds: Int = ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS
): Boolean {
    require(completedRounds >= 0) { "completedRounds must be non-negative" }
    require(maxRounds > 0) { "maxRounds must be positive" }
    return completedRounds in 1 until maxRounds
}

internal const val ACE_LIVE_STARTUP_DHT_RETURN_AFTER_PEERS = 1
internal const val ACE_LIVE_STARTUP_DHT_PROBE_RETURN_AFTER_PEERS = 4
internal const val ACE_LIVE_STARTUP_DHT_PROBE_BUDGET_MILLIS = 7_000L
internal const val ACE_LIVE_STARTUP_DHT_PROBE_MAX_ROUNDS = 2

internal const val ACE_LIVE_DHT_MIN_HEAP_HEADROOM_BYTES: Long = 32L * 1024L * 1024L

// Direct content-id startup and metadata resolution may legitimately race. They share the same
// process heap, so only one memory-heavy Mainline DHT walk is allowed at a time. Tracker fast paths
// remain fully concurrent and a queued DHT walk re-checks heap headroom immediately before starting.
private val ACE_LIVE_DHT_EXECUTION_MUTEX = Mutex()

internal fun aceLiveDhtHeapHeadroomBytes(
    maxMemoryBytes: Long,
    totalMemoryBytes: Long,
    freeMemoryBytes: Long
): Long {
    val maxMemory = maxMemoryBytes.coerceAtLeast(0L)
    val allocated = totalMemoryBytes.coerceAtLeast(0L)
    val freeAllocated = freeMemoryBytes.coerceIn(0L, allocated)
    val used = (allocated - freeAllocated).coerceAtLeast(0L)
    return (maxMemory - used).coerceAtLeast(0L)
}

internal fun aceLiveDhtHasHeapHeadroom(
    maxMemoryBytes: Long,
    totalMemoryBytes: Long,
    freeMemoryBytes: Long,
    minHeadroomBytes: Long = ACE_LIVE_DHT_MIN_HEAP_HEADROOM_BYTES
): Boolean {
    require(minHeadroomBytes >= 0L) { "minHeadroomBytes must be non-negative" }
    return aceLiveDhtHeapHeadroomBytes(
        maxMemoryBytes = maxMemoryBytes,
        totalMemoryBytes = totalMemoryBytes,
        freeMemoryBytes = freeMemoryBytes
    ) >= minHeadroomBytes
}

/**
 * Aggregates independent Ace Live discovery sources before the TCP connection pool.
 *
 * Tracker and Mainline DHT acquisition are started independently on the tracker-fast-path. DHT is
 * launched before waiting for the tracker, but a tracker batch that already satisfies the fast-path
 * threshold is returned immediately and never waits for DHT. The bounded DHT acquisition remains
 * available for the next DHT-only startup refill. If the tracker is weak or fails, that same already-
 * running acquisition becomes the synchronous fallback instead of starting a duplicate DHT walk.
 *
 * The speculative DHT registry owns at most one process-wide acquisition. A new swarm cancels an
 * older speculative walk so rapid channel switching does not leave obsolete work occupying the DHT
 * execution gate. Same-swarm refill may reuse the in-flight or just-completed result for a short
 * bounded lifetime. No network timeout, DHT query budget, peer cap or player buffer is widened.
 *
 * A final pre-handshake TCP connect failure is temporarily ineligible for the same swarm for exactly
 * the coordinator's existing first-failure backoff. Eligibility is snapshotted once per source result
 * so a concurrent failure cannot make the fast-path decision disagree with the returned candidate
 * list. Raw source summaries still report unfiltered discovery counts for diagnostics.
 *
 * Multiple orchestrator instances may be active while content-id startup races metadata resolution.
 * Their DHT work remains process-wide serialized and re-checks heap headroom after acquiring the
 * gate, preventing two expensive DHT walks from racing each other on low-memory TV devices.
 *
 * When the tracker fast path is disabled, DHT and tracker discovery retain the original concurrent
 * supervisor behavior. Coroutine cancellation is never converted into a source failure.
 */
class AceLivePeerDiscoveryOrchestrator(
    private val dhtDiscover: suspend (AceLiveDhtDiscoveryRequest) -> AceLiveDhtDiscoveryResult =
        { request -> AceLiveDhtDiscovery(reuseRecentResults = true).discover(request) },
    private val trackerDiscover: suspend (AceLiveUdpTrackerDiscoveryRequest) -> AceLiveUdpTrackerDiscoveryResult =
        { request -> AceLiveUdpTrackerDiscovery().discover(request) },
    private val policy: AceLivePeerDiscoveryOrchestrationPolicy =
        AceLivePeerDiscoveryOrchestrationPolicy(),
    private val dhtHeadroomAvailable: () -> Boolean = {
        val runtime = Runtime.getRuntime()
        aceLiveDhtHasHeapHeadroom(
            maxMemoryBytes = runtime.maxMemory(),
            totalMemoryBytes = runtime.totalMemory(),
            freeMemoryBytes = runtime.freeMemory()
        )
    },
    private val connectFailureMemory: AceLiveTcpConnectFailureMemory =
        AceLiveTcpConnectFailureMemory.shared
) {
    suspend fun discover(
        request: AceLivePeerDiscoveryOrchestrationRequest
    ): AceLivePeerDiscoveryOrchestrationResult = supervisorScope {
        val dhtRequest = request.dhtRequest
        val trackerRequest = request.trackerRequest
        val swarmKey = trackerRequest?.swarmKey ?: requireNotNull(dhtRequest).swarmKey
        val swarmBytes = swarmKey.toByteArray()
        val dhtPermitted = dhtRequest != null && dhtHeadroomAvailable()

        if (policy.preferTrackerFastPath && trackerRequest != null) {
            val backgroundDht = dhtRequest
                ?.takeIf { dhtPermitted }
                ?.let { sourceRequest ->
                    AceLiveBackgroundDhtAcquisitionRegistry.startOrReuse(swarmKey) {
                        runDhtSource(sourceRequest).toBackgroundOutcome()
                    }
                }
            val trackerExecution = captureSource { trackerDiscover(trackerRequest) }
            val eligibleTrackerPeers = when (trackerExecution) {
                is SourceExecution.Success -> eligiblePeers(
                    swarmKey = swarmBytes,
                    peers = trackerExecution.value.peers
                )
                else -> emptyList()
            }
            val fastPathThreshold = minOf(policy.trackerFastPathMinPeers, policy.maxTotalPeers)

            if (eligibleTrackerPeers.size >= fastPathThreshold || !dhtPermitted) {
                return@supervisorScope buildResult(
                    dhtExecution = SourceExecution.NotRequested,
                    trackerExecution = trackerExecution,
                    swarmKey = swarmBytes,
                    eligibleTrackerPeers = eligibleTrackerPeers
                )
            }

            val dhtExecution = backgroundDht?.let { lease ->
                awaitBackgroundDht(lease)
            } ?: SourceExecution.NotRequested
            return@supervisorScope buildResult(
                dhtExecution = dhtExecution,
                trackerExecution = trackerExecution,
                swarmKey = swarmBytes,
                eligibleTrackerPeers = eligibleTrackerPeers
            )
        }

        val reusableBackgroundDht = if (
            policy.preferTrackerFastPath &&
            trackerRequest == null &&
            dhtRequest != null
        ) {
            AceLiveBackgroundDhtAcquisitionRegistry.acquire(swarmKey)
        } else {
            null
        }
        val dhtDeferred = when {
            reusableBackgroundDht != null -> async {
                awaitBackgroundDht(reusableBackgroundDht)
            }
            dhtRequest != null && dhtPermitted -> async {
                runDhtSource(dhtRequest)
            }
            else -> null
        }
        val trackerDeferred = trackerRequest?.let { sourceRequest ->
            async { captureSource { trackerDiscover(sourceRequest) } }
        }

        val dhtExecution = dhtDeferred?.await() ?: SourceExecution.NotRequested
        val trackerExecution = trackerDeferred?.await() ?: SourceExecution.NotRequested
        buildResult(dhtExecution, trackerExecution, swarmBytes)
    }

    private suspend fun awaitBackgroundDht(
        lease: AceLiveBackgroundDhtAcquisitionRegistry.Lease
    ): SourceExecution<AceLiveDhtDiscoveryResult> {
        return try {
            lease.deferred.await().toSourceExecution()
        } finally {
            if (lease.deferred.isCompleted) {
                AceLiveBackgroundDhtAcquisitionRegistry.release(lease)
            }
        }
    }

    private suspend fun runDhtSource(
        request: AceLiveDhtDiscoveryRequest
    ): SourceExecution<AceLiveDhtDiscoveryResult> = ACE_LIVE_DHT_EXECUTION_MUTEX.withLock {
        if (!dhtHeadroomAvailable()) {
            SourceExecution.NotRequested
        } else {
            captureSource { dhtDiscover(request) }
        }
    }

    private fun buildResult(
        dhtExecution: SourceExecution<AceLiveDhtDiscoveryResult>,
        trackerExecution: SourceExecution<AceLiveUdpTrackerDiscoveryResult>,
        swarmKey: ByteArray,
        eligibleTrackerPeers: List<AceLiveTcpPeerEndpoint>? = null
    ): AceLivePeerDiscoveryOrchestrationResult {
        val discovered = LinkedHashMap<String, MutableDiscoveredPeer>()
        val dhtPeers = when (dhtExecution) {
            is SourceExecution.Success -> eligiblePeers(swarmKey, dhtExecution.value.peers)
            SourceExecution.Failed,
            SourceExecution.NotRequested -> emptyList()
        }
        val trackerPeers = eligibleTrackerPeers ?: when (trackerExecution) {
            is SourceExecution.Success -> eligiblePeers(swarmKey, trackerExecution.value.peers)
            SourceExecution.Failed,
            SourceExecution.NotRequested -> emptyList()
        }
        addPeers(
            target = discovered,
            peers = dhtPeers,
            source = AceLivePeerDiscoverySource.MAINLINE_DHT
        )
        addPeers(
            target = discovered,
            peers = trackerPeers,
            source = AceLivePeerDiscoverySource.UDP_TRACKER
        )
        val dhtResult = when (dhtExecution) {
            is SourceExecution.Success -> dhtExecution.value
            SourceExecution.Failed,
            SourceExecution.NotRequested -> null
        }

        return AceLivePeerDiscoveryOrchestrationResult(
            peers = discovered.values.map { value ->
                AceLiveDiscoveredPeer(
                    endpoint = value.endpoint,
                    sources = value.sources.toSet()
                )
            },
            dht = summarize(dhtExecution) { result -> result.peers.size },
            tracker = summarize(trackerExecution) { result -> result.peers.size },
            dhtQueriesSent = dhtResult?.queriesSent ?: 0,
            dhtFailedQueries = dhtResult?.failedQueries ?: 0,
            dhtWarmRoutingSeedsUsed = dhtResult?.warmRoutingSeedsUsed ?: 0,
            dhtCacheHit = dhtResult?.cacheHit ?: false,
            dhtAnnouncesSent = dhtResult?.announcesSent ?: 0,
            dhtAnnouncesSucceeded = dhtResult?.announcesSucceeded ?: 0
        )
    }

    private fun eligiblePeers(
        swarmKey: ByteArray,
        peers: List<AceLiveTcpPeerEndpoint>
    ): List<AceLiveTcpPeerEndpoint> = peers.filter { peer ->
        connectFailureMemory.isEligible(swarmKey, peer)
    }

    private fun addPeers(
        target: LinkedHashMap<String, MutableDiscoveredPeer>,
        peers: List<AceLiveTcpPeerEndpoint>,
        source: AceLivePeerDiscoverySource
    ) {
        for (peer in peers) {
            val key = endpointKey(peer)
            val existing = target[key]
            if (existing != null) {
                existing.sources += source
                continue
            }
            if (target.size >= policy.maxTotalPeers) continue
            target[key] = MutableDiscoveredPeer(
                endpoint = peer,
                sources = linkedSetOf(source)
            )
        }
    }

    private suspend fun <T> captureSource(block: suspend () -> T): SourceExecution<T> = try {
        SourceExecution.Success(block())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        SourceExecution.Failed
    }

    private fun SourceExecution<AceLiveDhtDiscoveryResult>.toBackgroundOutcome():
        AceLiveBackgroundDhtAcquisitionRegistry.Outcome = when (this) {
        SourceExecution.NotRequested -> AceLiveBackgroundDhtAcquisitionRegistry.Outcome.NotRequested
        SourceExecution.Failed -> AceLiveBackgroundDhtAcquisitionRegistry.Outcome.Failed
        is SourceExecution.Success -> AceLiveBackgroundDhtAcquisitionRegistry.Outcome.Success(value)
    }

    private fun AceLiveBackgroundDhtAcquisitionRegistry.Outcome.toSourceExecution():
        SourceExecution<AceLiveDhtDiscoveryResult> = when (this) {
        AceLiveBackgroundDhtAcquisitionRegistry.Outcome.NotRequested -> SourceExecution.NotRequested
        AceLiveBackgroundDhtAcquisitionRegistry.Outcome.Failed -> SourceExecution.Failed
        is AceLiveBackgroundDhtAcquisitionRegistry.Outcome.Success -> SourceExecution.Success(value)
    }

    private fun <T> summarize(
        execution: SourceExecution<T>,
        peerCount: (T) -> Int
    ): AceLivePeerDiscoverySourceSummary = when (execution) {
        SourceExecution.NotRequested -> AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
            returnedPeerCount = 0
        )
        SourceExecution.Failed -> AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.FAILED,
            returnedPeerCount = 0
        )
        is SourceExecution.Success -> AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
            returnedPeerCount = peerCount(execution.value)
        )
    }

    private fun endpointKey(endpoint: AceLiveTcpPeerEndpoint): String =
        "${endpoint.host}:${endpoint.port}"

    private data class MutableDiscoveredPeer(
        val endpoint: AceLiveTcpPeerEndpoint,
        val sources: MutableSet<AceLivePeerDiscoverySource>
    )

    private sealed interface SourceExecution<out T> {
        data object NotRequested : SourceExecution<Nothing>
        data object Failed : SourceExecution<Nothing>
        data class Success<T>(val value: T) : SourceExecution<T>
    }
}
