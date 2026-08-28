package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveUdpTrackerStartupFastPathTest {
    @Test
    fun `startup batch returns immediately and later refill continues supplemental sources`() =
        runBlocking {
            var fastPathClaimed = false
            val discovery = AceLiveUdpTrackerDiscovery(
                startupFastPathClaim = { _, _ ->
                    if (fastPathClaimed) {
                        false
                    } else {
                        fastPathClaimed = true
                        true
                    }
                }
            )
            val request = request(AceLiveSwarmKey.parseHex(SWARM_HEX)!!)
            val expectedStartupPeers = startupPeers()

            val first = discovery.discover(request)

            assertEquals(expectedStartupPeers, first.peers)
            assertEquals(2, first.attemptedTrackers)
            assertEquals(0, first.failedTrackers)
            assertEquals(0, first.rejectedTrackers)
            assertTrue(fastPathClaimed)

            val supplemental = discovery.discover(request)

            assertEquals(expectedStartupPeers, supplemental.peers)
            assertEquals(2, supplemental.attemptedTrackers)
            assertEquals(0, supplemental.failedTrackers)
            assertEquals(1, supplemental.rejectedTrackers)
        }

    @Test
    fun `independent runtime claims do not share one-shot state`() = runBlocking {
        var firstRuntimeClaimed = false
        var reopenedRuntimeClaimed = false
        val firstRuntime = AceLiveUdpTrackerDiscovery(
            startupFastPathClaim = { _, _ ->
                if (firstRuntimeClaimed) false else true.also { firstRuntimeClaimed = true }
            }
        )
        val reopenedRuntime = AceLiveUdpTrackerDiscovery(
            startupFastPathClaim = { _, _ ->
                if (reopenedRuntimeClaimed) false else true.also { reopenedRuntimeClaimed = true }
            }
        )
        val firstRequest = request(AceLiveSwarmKey.parseHex(SWARM_HEX)!!)
        val reopenedRequest = request(AceLiveSwarmKey.parseHex(SWARM_HEX)!!)

        val first = firstRuntime.discover(firstRequest)
        val sameRuntimeRefill = firstRuntime.discover(firstRequest)
        val reopened = reopenedRuntime.discover(reopenedRequest)

        assertEquals(startupPeers(), first.peers)
        assertEquals(0, first.rejectedTrackers)
        assertEquals(startupPeers(), sameRuntimeRefill.peers)
        assertEquals(1, sameRuntimeRefill.rejectedTrackers)
        assertEquals(startupPeers(), reopened.peers)
        assertEquals(0, reopened.rejectedTrackers)
        assertTrue(firstRuntimeClaimed)
        assertTrue(reopenedRuntimeClaimed)
    }

    private fun request(swarmKey: AceLiveSwarmKey) = AceLiveUdpTrackerDiscoveryRequest(
        swarmKey = swarmKey,
        trackers = listOf(
            "ace-startup:8.8.8.8:8621",
            "ace-startup:1.1.1.1:8632",
            "not-a-valid-discovery-source"
        ),
        peerId = ByteArray(AceLiveUdpTrackerCodec.PEER_ID_BYTES) { index -> index.toByte() },
        announcePort = 8621
    )

    private fun startupPeers() = listOf(
        AceLiveTcpPeerEndpoint("8.8.8.8", 8621),
        AceLiveTcpPeerEndpoint("1.1.1.1", 8632)
    )

    private companion object {
        const val SWARM_HEX = "00112233445566778899aabbccddeeff00112233"
    }
}
