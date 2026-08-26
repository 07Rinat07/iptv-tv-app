package com.iptv.tv.core.p2p

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
    fun `DHT bootstrap roots remain bounded and unique`() {
        val nodes = AceLiveNetworkDefaults.dhtBootstrapNodes

        assertTrue(nodes.size <= AceLiveDhtPolicy().maxBootstrapNodes)
        assertEquals(nodes.size, nodes.distinct().size)
    }
}
