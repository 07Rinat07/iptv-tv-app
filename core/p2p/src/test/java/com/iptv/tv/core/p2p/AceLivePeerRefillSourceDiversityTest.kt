package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerRefillSourceDiversityTest {
    @Test
    fun `equal quality refill spans independent public discovery classes`() {
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        val trackerFirst = endpoint("1.1.1.1", 8621)
        val trackerSecond = endpoint("2.2.2.2", 8621)
        val dht = endpoint("9.9.9.9", 8621)

        coordinator.ingestDiscovery(
            discovery(
                trackerFirst to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                trackerSecond to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                dht to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            ),
            nowMillis = 1_000L
        )

        val plan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = null,
            poolStale = false,
            nowMillis = 1_000L
        )

        assertEquals(2, plan.candidates.size)
        assertTrue(
            plan.candidates.any { candidate ->
                AceLivePeerDiscoverySource.UDP_TRACKER in candidate.sources
            }
        )
        assertTrue(
            plan.candidates.any { candidate ->
                AceLivePeerDiscoverySource.MAINLINE_DHT in candidate.sources
            }
        )
    }

    @Test
    fun `discovery refresh retains public source provenance for candidate lifetime`() {
        val coordinator = coordinator(target = 1, max = 1, maxStarts = 1)
        val peer = endpoint("8.8.8.8", 8621)

        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)),
            nowMillis = 1_000L
        )
        coordinator.ingestDiscovery(
            discovery(peer to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)),
            nowMillis = 2_000L
        )

        assertEquals(
            setOf(
                AceLivePeerDiscoverySource.MAINLINE_DHT,
                AceLivePeerDiscoverySource.UDP_TRACKER
            ),
            requireNotNull(coordinator.snapshot(peer)).sources
        )
    }

    @Test
    fun `source diversity never outranks verified useful window`() {
        val coordinator = coordinator(target = 2, max = 2, maxStarts = 2)
        val usefulFirst = endpoint("1.0.0.1", 8621)
        val usefulSecond = endpoint("1.0.0.2", 8621)
        val unknownDht = endpoint("9.9.9.9", 8621)

        coordinator.ingestDiscovery(
            discovery(
                usefulFirst to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                usefulSecond to setOf(AceLivePeerDiscoverySource.UDP_TRACKER)
            ),
            nowMillis = 1_000L
        )
        val initial = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = 105L,
            poolStale = false,
            nowMillis = 1_000L
        )
        initial.candidates.forEachIndexed { index, candidate ->
            val peerId = (index + 1).toLong()
            coordinator.beginStart(peerId, candidate.endpoint)
            coordinator.markStartAccepted(peerId)
            coordinator.onPoolEvent(
                AceLiveTcpPoolEvent.TransportConnected(peerId = peerId, reconnectAttempt = 0),
                nowMillis = 1_050L
            )
            coordinator.onPoolEvent(
                AceLiveTcpPoolEvent.Ingress(
                    peerId = peerId,
                    result = AceLivePeerIngressResult(
                        metadataUpdates = listOf(window(100L, 120L))
                    )
                ),
                nowMillis = 1_100L
            )
        }
        coordinator.syncActivePeerIds(emptySet())

        coordinator.ingestDiscovery(
            discovery(
                usefulFirst to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                usefulSecond to setOf(AceLivePeerDiscoverySource.UDP_TRACKER),
                unknownDht to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
            ),
            nowMillis = 2_000L
        )

        val plan = coordinator.planRefill(
            activePeerIds = emptySet(),
            nextNeededPiece = 105L,
            poolStale = false,
            nowMillis = 2_000L
        )

        assertEquals(setOf(usefulFirst, usefulSecond), plan.candidates.map { it.endpoint }.toSet())
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
        val dhtCount = values.count { peer ->
            AceLivePeerDiscoverySource.MAINLINE_DHT in peer.sources
        }
        val trackerCount = values.count { peer ->
            AceLivePeerDiscoverySource.UDP_TRACKER in peer.sources
        }
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
}
