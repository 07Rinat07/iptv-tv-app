package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLivePeerRefillConnectFailureMemoryTest {
    @Test
    fun `shared transport backoff skips failed endpoint and restores it after expiry`() {
        var now = 10_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { now },
            backoffMillis = 5_000L
        )
        val swarmKey = ByteArray(AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) { 0x44.toByte() }
        val failed = AceLiveTcpPeerEndpoint("1.1.1.1", 8621)
        val healthy = AceLiveTcpPeerEndpoint("9.9.9.9", 8621)
        memory.recordFinalPreHandshakeFailure(swarmKey, failed, nowMillis = now)

        val coordinator = AceLivePeerRefillCoordinator(
            policy = AceLivePeerRefillPolicy(
                targetActivePeers = 1,
                maxActivePeers = 2,
                staleProbePeers = 0,
                maxStartsPerCycle = 1,
                refreshIntervalMillis = 1_000L,
                candidateTtlMillis = 60_000L,
                failureBackoffBaseMillis = 1_000L,
                failureBackoffMaxMillis = 8_000L
            ),
            swarmKey = swarmKey,
            connectFailureMemory = memory
        )
        coordinator.ingestDiscovery(
            discovery(failed, healthy),
            nowMillis = now
        )

        val blockedPlan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = now
        )

        assertEquals(listOf(healthy), blockedPlan.candidates.map { it.endpoint })
        coordinator.releaseReservation(healthy)

        now += 5_000L
        val expiredPlan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = now
        )

        assertEquals(listOf(failed), expiredPlan.candidates.map { it.endpoint })
    }

    private fun discovery(
        vararg endpoints: AceLiveTcpPeerEndpoint
    ): AceLivePeerDiscoveryOrchestrationResult = AceLivePeerDiscoveryOrchestrationResult(
        peers = endpoints.map { endpoint ->
            AceLiveDiscoveredPeer(
                endpoint = endpoint,
                sources = setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            )
        },
        dht = AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
            returnedPeerCount = endpoints.size
        ),
        tracker = AceLivePeerDiscoverySourceSummary(
            status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
            returnedPeerCount = 0
        )
    )
}
