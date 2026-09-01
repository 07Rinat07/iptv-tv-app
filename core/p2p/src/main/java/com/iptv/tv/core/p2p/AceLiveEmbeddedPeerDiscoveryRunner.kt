package com.iptv.tv.core.p2p

/**
 * Instance-local seam around the final peer-discovery orchestration call used by the embedded
 * runtime. Production keeps the exact existing orchestrator and source-specific discovery logic;
 * deterministic qualification can replace only the acquisition result without weakening tracker,
 * DHT, transport, handshake, scheduling, authentication, buffering, or playback semantics.
 */
internal fun interface AceLiveEmbeddedPeerDiscoveryRunner {
    suspend fun discover(
        dhtDiscover: suspend (AceLiveDhtDiscoveryRequest) -> AceLiveDhtDiscoveryResult,
        policy: AceLivePeerDiscoveryOrchestrationPolicy,
        request: AceLivePeerDiscoveryOrchestrationRequest
    ): AceLivePeerDiscoveryOrchestrationResult

    companion object {
        val Production = AceLiveEmbeddedPeerDiscoveryRunner { dhtDiscover, policy, request ->
            val parallelTrackerDiscovery = AceLiveParallelTrackerDiscovery()
            AceLivePeerDiscoveryOrchestrator(
                dhtDiscover = dhtDiscover,
                trackerDiscover = parallelTrackerDiscovery::discover,
                policy = policy
            ).discover(request)
        }
    }
}
