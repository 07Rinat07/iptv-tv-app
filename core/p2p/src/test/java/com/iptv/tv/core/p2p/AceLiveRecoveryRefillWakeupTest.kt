package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveRecoveryRefillWakeupTest {
    @Test
    fun `timed out piece requests one bounded alternative probe`() {
        assertEquals(
            1,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(
                    timedOutRequests = listOf(
                        AceLiveTimedOutRequest(
                            piece = 42L,
                            previousPeerId = 7L,
                            acceptedChunks = 32,
                            assignmentAgeMillis = 8_000L,
                            progressAgeMillis = 6_000L
                        )
                    )
                )
            )
        )
    }

    @Test
    fun `multiple timed out pieces still request only one alternative probe`() {
        assertEquals(
            1,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(
                    timedOutRequests = listOf(
                        AceLiveTimedOutRequest(piece = 42L, previousPeerId = 7L),
                        AceLiveTimedOutRequest(piece = 43L, previousPeerId = 8L)
                    )
                )
            )
        )
    }

    @Test
    fun `one shot demand conflates repeated requests and clears after consume`() {
        val demand = AceLiveOneShotPeerProbeDemand()

        repeat(8) { demand.request(1) }

        assertEquals(1, demand.consume())
        assertEquals(0, demand.consume())
    }

    @Test
    fun `one shot recovery probe can acquire an alternative above satisfied baseline target`() =
        runBlocking {
            val known = AceLiveTcpPeerEndpoint(host = "192.0.2.60", port = 8621)
            val active = linkedSetOf(1L)
            val coordinator = AceLivePeerRefillCoordinator(
                policy = AceLivePeerRefillPolicy(
                    targetActivePeers = 1,
                    maxActivePeers = 2,
                    staleProbePeers = 0,
                    maxStartsPerCycle = 1,
                    refreshIntervalMillis = 60_000L,
                    candidateTtlMillis = 120_000L,
                    failureBackoffBaseMillis = 1_000L,
                    failureBackoffMaxMillis = 8_000L
                )
            )
            coordinator.onPoolEvent(
                AceLiveTcpPoolEvent.Ingress(
                    peerId = 1L,
                    result = AceLivePeerIngressResult(peerExchangePeers = listOf(known))
                ),
                nowMillis = 1_000L
            )
            val demand = AceLiveOneShotPeerProbeDemand().also { it.request(1) }
            var nextPeerId = 100L
            var discoveryCalls = 0
            val loop = AceLivePeerRefillLoop(
                coordinator = coordinator,
                discover = {
                    discoveryCalls += 1
                    emptyDiscovery()
                },
                activePeerIds = { active.toSet() },
                evaluateRecovery = { AceLiveRecoveryPlan() },
                nextNeededPiece = { null },
                allocatePeerId = { nextPeerId++ },
                startPeer = { peerId, endpoint ->
                    assertEquals(known, endpoint)
                    active += peerId
                },
                clockMillis = { 1_000L },
                adaptiveProbePeers = { demand.consume() }
            )

            val result = loop.runOneCycle(1_000L)

            assertEquals(1, result.startedPeers)
            assertEquals(0, discoveryCalls)
            assertEquals(2, active.size)
            assertEquals(0, demand.consume())

            val followUp = loop.runOneCycle(1_001L)
            assertEquals(0, followUp.startedPeers)
            assertEquals(0, followUp.plannedStarts)
        }

    @Test
    fun `stale pool alone does not create level triggered probe demand`() {
        assertEquals(
            0,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(poolStale = true)
            )
        )
    }

    @Test
    fun `cursor advance alone does not request alternative probe`() {
        assertEquals(
            0,
            aceLiveRecoveryRefillProbePeers(
                AceLiveRecoveryPlan(
                    cursorAdvance = AceLiveCursorAdvance(fromPiece = 42L, toPiece = 43L)
                )
            )
        )
    }

    private fun emptyDiscovery(): AceLivePeerDiscoveryOrchestrationResult =
        AceLivePeerDiscoveryOrchestrationResult(
            peers = emptyList(),
            dht = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
                returnedPeerCount = 0
            ),
            tracker = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
                returnedPeerCount = 0
            )
        )
}
