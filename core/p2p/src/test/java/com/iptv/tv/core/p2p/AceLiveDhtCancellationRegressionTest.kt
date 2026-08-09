package com.iptv.tv.core.p2p

import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveDhtCancellationRegressionTest {
    @Test
    fun `cancellation closes a silent DHT receive without waiting for socket timeout`() = runBlocking {
        val silent = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        try {
            val discovery = AceLiveDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 5_000,
                    discoveryBudgetMillis = 5_000,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) }
            )
            val request = AceLiveDhtDiscoveryRequest(
                swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                bootstrapNodes = listOf(AceLiveDhtBootstrapNode("silent.test", silent.localPort)),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
            )

            val job = launch { discovery.discover(request) }
            delay(150)
            val cancelMillis = measureTimeMillis { job.cancelAndJoin() }

            assertTrue(
                "cancellation should interrupt socket receive, took ${cancelMillis}ms",
                cancelMillis < 2_000
            )
        } finally {
            silent.close()
        }
    }

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
