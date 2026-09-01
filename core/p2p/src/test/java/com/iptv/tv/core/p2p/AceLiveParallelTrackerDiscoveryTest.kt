package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveParallelTrackerDiscoveryTest {
    @Test
    fun `independent tracker sources run concurrently and fast path does not wait for dead sources`() =
        runBlocking {
            val activeCalls = AtomicInteger(0)
            val maxObservedCalls = AtomicInteger(0)
            val discovery = AceLiveParallelTrackerDiscovery(
                maxConcurrentSources = 3,
                fastPathMinPeers = 4,
                fastPathTargetPeers = 24,
                aggregationGraceMillis = 100,
                singleSourceDiscover = { request ->
                    val active = activeCalls.incrementAndGet()
                    maxObservedCalls.updateAndGet { previous -> maxOf(previous, active) }
                    try {
                        when (request.trackers.single()) {
                            "udp://slow-one.example:80/announce" -> {
                                delay(2_000)
                                result(emptyList())
                            }

                            "udp://good.example:80/announce" -> {
                                delay(40)
                                result(
                                    listOf(
                                        peer("8.8.8.1", 1001),
                                        peer("8.8.8.2", 1002),
                                        peer("8.8.8.3", 1003),
                                        peer("8.8.8.4", 1004)
                                    )
                                )
                            }

                            else -> {
                                delay(2_000)
                                result(emptyList())
                            }
                        }
                    } finally {
                        activeCalls.decrementAndGet()
                    }
                }
            )

            val result = withTimeout(500) {
                discovery.discover(
                    request(
                        listOf(
                            "udp://slow-one.example:80/announce",
                            "udp://good.example:80/announce",
                            "udp://slow-two.example:80/announce"
                        )
                    )
                )
            }

            assertEquals(4, result.peers.size)
            assertTrue("tracker sources were still serialized", maxObservedCalls.get() >= 2)
        }

    @Test
    fun `aggregation grace merges completed tracker results and removes duplicate endpoints`() = runBlocking {
        val discovery = AceLiveParallelTrackerDiscovery(
            maxConcurrentSources = 3,
            fastPathMinPeers = 100,
            fastPathTargetPeers = 100,
            aggregationGraceMillis = 0,
            singleSourceDiscover = { request ->
                when (request.trackers.single()) {
                    "udp://one.example:80/announce" -> result(
                        listOf(peer("8.8.8.1", 1001), peer("8.8.8.2", 1002)),
                        attempted = 1
                    )

                    "udp://two.example:80/announce" -> result(
                        listOf(peer("8.8.8.2", 1002), peer("8.8.8.3", 1003)),
                        attempted = 2
                    )

                    else -> result(
                        listOf(peer("8.8.8.4", 1004)),
                        attempted = 1,
                        failed = 1
                    )
                }
            }
        )

        val result = discovery.discover(
            request(
                listOf(
                    "udp://one.example:80/announce",
                    "udp://two.example:80/announce",
                    "udp://three.example:80/announce"
                )
            )
        )

        assertEquals(4, result.peers.size)
        assertEquals(4, result.attemptedTrackers)
        assertEquals(1, result.failedTrackers)
        assertEquals(
            setOf("8.8.8.1:1001", "8.8.8.2:1002", "8.8.8.3:1003", "8.8.8.4:1004"),
            result.peers.map { peer -> "${peer.host}:${peer.port}" }.toSet()
        )
    }

    private fun request(trackers: List<String>): AceLiveUdpTrackerDiscoveryRequest =
        AceLiveUdpTrackerDiscoveryRequest(
            swarmKey = AceLiveSwarmKey.parseHex("00112233445566778899aabbccddeeff00112233")!!,
            trackers = trackers,
            peerId = ByteArray(AceLiveUdpTrackerCodec.PEER_ID_BYTES) { index -> index.toByte() },
            announcePort = 8621
        )

    private fun peer(host: String, port: Int): AceLiveTcpPeerEndpoint =
        AceLiveTcpPeerEndpoint(host, port)

    private fun result(
        peers: List<AceLiveTcpPeerEndpoint>,
        attempted: Int = 1,
        failed: Int = 0,
        rejected: Int = 0
    ): AceLiveUdpTrackerDiscoveryResult = AceLiveUdpTrackerDiscoveryResult(
        peers = peers,
        attemptedTrackers = attempted,
        failedTrackers = failed,
        rejectedTrackers = rejected
    )
}
