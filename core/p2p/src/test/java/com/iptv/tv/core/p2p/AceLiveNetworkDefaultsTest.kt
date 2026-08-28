package com.iptv.tv.core.p2p

import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveNetworkDefaultsTest {
    @Test
    fun `DHT bootstrap roots retain independent public operators`() {
        val nodes = AceLiveNetworkDefaults.dhtBootstrapNodes

        assertTrue(nodes.size >= 5)
        assertTrue(nodes.contains(AceLiveDhtBootstrapNode("dht.transmissionbt.com", 6881)))
        assertTrue(nodes.contains(AceLiveDhtBootstrapNode("dht.aelitis.com", 6881)))
        assertTrue(nodes.contains(AceLiveDhtBootstrapNode("dht.libtorrent.org", 25401)))
    }

    @Test
    fun `tracker announce lease transfers accepted socket to runtime owner`() {
        val accepted = ArrayBlockingQueue<Socket>(1)

        AceLiveAnnouncePortLease { socket -> accepted.offer(socket) }.use { lease ->
            Socket("127.0.0.1", lease.port).use { client ->
                val inbound = checkNotNull(accepted.poll(2, TimeUnit.SECONDS))
                try {
                    client.getOutputStream().write(0x2a)
                    client.getOutputStream().flush()
                    assertEquals(0x2a, inbound.getInputStream().read())
                } finally {
                    inbound.close()
                }
            }
        }
    }

    @Test
    fun `DHT bootstrap roots remain bounded and unique`() {
        val nodes = AceLiveNetworkDefaults.dhtBootstrapNodes

        assertTrue(nodes.size <= AceLiveDhtPolicy().maxBootstrapNodes)
        assertEquals(nodes.size, nodes.distinct().size)
    }
}
