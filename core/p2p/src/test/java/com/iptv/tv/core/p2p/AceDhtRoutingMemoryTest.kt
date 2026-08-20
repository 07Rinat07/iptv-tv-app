package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceDhtRoutingMemoryTest {
    @Test
    fun `routing memory is LRU bounded and expires by monotonic ttl`() {
        var nowNanos = 0L
        val memory = AceDhtRoutingMemory(
            maxNodes = 2,
            ttlMillis = 100,
            clockNanos = { nowNanos }
        )
        val first = contact(host = "8.8.8.8", port = 8601, nodeByte = 0x11)
        val second = contact(host = "1.1.1.1", port = 8602, nodeByte = 0x22)
        val third = contact(host = "9.9.9.9", port = 8603, nodeByte = 0x33)

        memory.remember(first)
        nowNanos = 1_000_000L
        memory.remember(second)
        nowNanos = 2_000_000L
        memory.remember(third)

        assertEquals(listOf(third, second), memory.recentContacts(limit = 8))

        nowNanos = 101_000_000L
        assertEquals(listOf(third), memory.recentContacts(limit = 8))
    }

    @Test
    fun `warm selection deduplicates node ids and ipv4 networks`() {
        val memory = AceDhtRoutingMemory(maxNodes = 8)
        val oldEndpointForSameNode = contact("8.8.8.8", 8601, 0x11)
        val newEndpointForSameNode = contact("1.1.1.1", 8602, 0x11)
        val sameNetworkOlder = contact("9.9.9.1", 8603, 0x22)
        val sameNetworkNewer = contact("9.9.9.2", 8604, 0x33)

        memory.remember(oldEndpointForSameNode)
        memory.remember(newEndpointForSameNode)
        memory.remember(sameNetworkOlder)
        memory.remember(sameNetworkNewer)

        assertEquals(
            listOf(sameNetworkNewer, newEndpointForSameNode),
            memory.recentContacts(limit = 8)
        )
    }

    private fun contact(host: String, port: Int, nodeByte: Int) = AceLiveDhtNodeContact(
        nodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { nodeByte.toByte() }),
        endpoint = AceLiveTcpPeerEndpoint(host, port)
    )
}
