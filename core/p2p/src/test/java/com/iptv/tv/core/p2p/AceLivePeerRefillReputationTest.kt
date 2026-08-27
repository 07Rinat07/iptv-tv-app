package com.iptv.tv.core.p2p

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class AceLivePeerRefillReputationTest {
    @Test
    fun `recent productive same-swarm peer ranks ahead of unknown candidate`() {
        val now = 1_000_000L
        val swarm = swarm(0x11)
        val preferred = AceLiveTcpPeerEndpoint("9.9.9.9", 6881)
        val unknown = AceLiveTcpPeerEndpoint("1.1.1.1", 6882)
        val store = store(now)
        store.recordMediaProduced(swarm, preferred, now - 1_000L)
        val coordinator = AceLivePeerRefillCoordinator(
            policy = AceLivePeerRefillPolicy(
                targetActivePeers = 1,
                maxActivePeers = 2,
                maxStartsPerCycle = 1,
                refreshIntervalMillis = 1_000L,
                candidateTtlMillis = 10_000L
            ),
            swarmKey = swarm,
            reputationStore = store
        )
        coordinator.ingestDiscovery(
            discovery(unknown, preferred),
            nowMillis = now
        )

        val plan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = now
        )

        assertEquals(listOf(preferred), plan.candidates.map(AceLivePeerRefillCandidate::endpoint))
    }

    @Test
    fun `qualified and producing events are persisted outside runtime candidate state`() {
        val now = 2_000_000L
        val swarm = swarm(0x22)
        val endpoint = AceLiveTcpPeerEndpoint("8.8.8.8", 6883)
        val store = store(now)
        val coordinator = AceLivePeerRefillCoordinator(
            policy = AceLivePeerRefillPolicy(
                targetActivePeers = 1,
                maxActivePeers = 2,
                maxStartsPerCycle = 1,
                refreshIntervalMillis = 1_000L,
                candidateTtlMillis = 10_000L
            ),
            swarmKey = swarm,
            reputationStore = store
        )
        coordinator.ingestDiscovery(discovery(endpoint), nowMillis = now)
        val candidate = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = now
        ).candidates.single()
        coordinator.beginStart(peerId = 7L, endpoint = candidate.endpoint)
        coordinator.markStartAccepted(7L)

        coordinator.onPoolEvent(AceLiveTcpPoolEvent.HandshakeAccepted(7L), now)
        assertEquals(1, store.snapshot(swarm, endpoint, now)?.priorityRank(now))

        coordinator.markMediaProduced(7L, now + 1L)
        assertEquals(0, store.snapshot(swarm, endpoint, now + 1L)?.priorityRank(now + 1L))
    }

    private fun discovery(
        vararg endpoints: AceLiveTcpPeerEndpoint
    ) = AceLivePeerDiscoveryOrchestrationResult(
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

    private fun store(now: Long): FileAceLivePeerReputationStore {
        val file = Files.createTempDirectory("ace-refill-reputation").resolve("peers.tsv").toFile()
        return FileAceLivePeerReputationStore(file, clockMillis = { now })
    }

    private fun swarm(value: Int): ByteArray = ByteArray(
        AceLivePeerHandshakeCodec.SWARM_KEY_BYTES
    ) { value.toByte() }
}
