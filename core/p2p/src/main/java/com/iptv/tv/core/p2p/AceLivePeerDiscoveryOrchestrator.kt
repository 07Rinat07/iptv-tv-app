package com.iptv.tv.core.p2p

import java.util.concurrent.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

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
    val maxTotalPeers: Int = 256
) {
    init {
        require(maxTotalPeers in 1..2_048) { "maxTotalPeers must be in 1..2048" }
    }
}

data class AceLivePeerDiscoveryOrchestrationResult(
    val peers: List<AceLiveDiscoveredPeer>,
    val dht: AceLivePeerDiscoverySourceSummary,
    val tracker: AceLivePeerDiscoverySourceSummary
) {
    fun tcpEndpoints(): List<AceLiveTcpPeerEndpoint> = peers.map(AceLiveDiscoveredPeer::endpoint)
}

/**
 * Aggregates independent Ace Live discovery sources before the TCP connection pool.
 *
 * DHT and tracker discovery run concurrently under a supervisor so an ordinary source failure does
 * not cancel the other source. Coroutine cancellation is never converted into a source failure.
 * Deduplication is endpoint-based and keeps source provenance for the later scoring/refill layer.
 */
class AceLivePeerDiscoveryOrchestrator(
    private val dhtDiscover: suspend (AceLiveDhtDiscoveryRequest) -> AceLiveDhtDiscoveryResult =
        { request -> AceLiveDhtDiscovery().discover(request) },
    private val trackerDiscover: suspend (AceLiveUdpTrackerDiscoveryRequest) -> AceLiveUdpTrackerDiscoveryResult =
        { request -> AceLiveUdpTrackerDiscovery().discover(request) },
    private val policy: AceLivePeerDiscoveryOrchestrationPolicy =
        AceLivePeerDiscoveryOrchestrationPolicy()
) {
    suspend fun discover(
        request: AceLivePeerDiscoveryOrchestrationRequest
    ): AceLivePeerDiscoveryOrchestrationResult = supervisorScope {
        val dhtDeferred = request.dhtRequest?.let { sourceRequest ->
            async { captureSource { dhtDiscover(sourceRequest) } }
        }
        val trackerDeferred = request.trackerRequest?.let { sourceRequest ->
            async { captureSource { trackerDiscover(sourceRequest) } }
        }

        val dhtExecution = dhtDeferred?.await() ?: SourceExecution.NotRequested
        val trackerExecution = trackerDeferred?.await() ?: SourceExecution.NotRequested

        val discovered = LinkedHashMap<String, MutableDiscoveredPeer>()
        if (dhtExecution is SourceExecution.Success) {
            addPeers(
                target = discovered,
                peers = dhtExecution.value.peers,
                source = AceLivePeerDiscoverySource.MAINLINE_DHT
            )
        }
        if (trackerExecution is SourceExecution.Success) {
            addPeers(
                target = discovered,
                peers = trackerExecution.value.peers,
                source = AceLivePeerDiscoverySource.UDP_TRACKER
            )
        }

        AceLivePeerDiscoveryOrchestrationResult(
            peers = discovered.values.map { value ->
                AceLiveDiscoveredPeer(
                    endpoint = value.endpoint,
                    sources = value.sources.toSet()
                )
            },
            dht = summarize(dhtExecution) { result -> result.peers.size },
            tracker = summarize(trackerExecution) { result -> result.peers.size }
        )
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
