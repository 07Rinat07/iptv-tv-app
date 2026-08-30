package com.iptv.tv.core.p2p

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class AceLiveEmbeddedDiscoveryRunnerContractTest {
    @Test
    fun `production runner delegates to existing orchestrator without changing request identity`() = runBlocking {
        val swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { index -> (0x20 + index).toByte() })
        val dhtRequest = AceLiveDhtDiscoveryRequest(
            swarmKey = swarmKey,
            bootstrapNodes = listOf(AceLiveDhtBootstrapNode("router.bittorrent.com", 6881))
        )
        val request = AceLivePeerDiscoveryOrchestrationRequest(dhtRequest = dhtRequest)
        val endpoint = AceLiveTcpPeerEndpoint("203.0.113.10", 41000)
        var seenRequest: AceLiveDhtDiscoveryRequest? = null

        val result = AceLiveEmbeddedPeerDiscoveryRunner.Production.discover(
            dhtDiscover = { supplied ->
                seenRequest = supplied
                AceLiveDhtDiscoveryResult(
                    peers = listOf(endpoint),
                    queriesSent = 1,
                    failedQueries = 0,
                    rejectedEndpoints = 0
                )
            },
            policy = AceLivePeerDiscoveryOrchestrationPolicy(preferTrackerFastPath = false),
            request = request
        )

        assertSame(dhtRequest, seenRequest)
        assertEquals(listOf(endpoint), result.tcpEndpoints())
        assertEquals(AceLivePeerDiscoverySourceStatus.SUCCEEDED, result.dht.status)
        assertEquals(1, result.dht.returnedPeerCount)
        assertEquals(AceLivePeerDiscoverySourceStatus.NOT_REQUESTED, result.tracker.status)
    }
}
