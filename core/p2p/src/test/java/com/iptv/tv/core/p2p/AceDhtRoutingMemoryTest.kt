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

    @Test
    fun `recent persisted contacts restore without refreshing their age`() {
        var nowEpochMillis = 10_000L
        var nowNanos = 5_000_000_000L
        val recent = contact("8.8.4.4", 6881, 0x44)
        val expired = contact("1.0.0.1", 6881, 0x55)
        val persistence = FakePersistence(
            loaded = listOf(
                AceDhtPersistedContact(recent, lastSeenEpochMillis = 9_950L),
                AceDhtPersistedContact(expired, lastSeenEpochMillis = 9_899L)
            )
        )

        val memory = AceDhtRoutingMemory(
            maxNodes = 8,
            ttlMillis = 100,
            clockNanos = { nowNanos },
            wallClockMillis = { nowEpochMillis },
            persistence = persistence
        )

        assertEquals(listOf(recent), memory.recentContacts(limit = 8))

        nowEpochMillis = 10_051L
        nowNanos += 51_000_000L
        assertEquals(emptyList<AceLiveDhtNodeContact>(), memory.recentContacts(limit = 8))
    }

    @Test
    fun `flush persists changed routing set and forget removes failed warm contact`() {
        var nowEpochMillis = 20_000L
        var nowNanos = 1_000_000_000L
        val persistence = FakePersistence()
        val memory = AceDhtRoutingMemory(
            maxNodes = 8,
            ttlMillis = 1_000,
            clockNanos = { nowNanos },
            wallClockMillis = { nowEpochMillis },
            persistence = persistence
        )
        val contact = contact("9.9.9.9", 6881, 0x66)

        memory.remember(contact)
        memory.flush()
        assertEquals(listOf(contact), persistence.saved.map(AceDhtPersistedContact::contact))
        assertEquals(listOf(20_000L), persistence.saved.map(AceDhtPersistedContact::lastSeenEpochMillis))

        nowEpochMillis += 10L
        nowNanos += 10_000_000L
        memory.forget(contact)
        memory.flush()
        assertEquals(emptyList<AceDhtPersistedContact>(), persistence.saved)
    }

    private fun contact(host: String, port: Int, nodeByte: Int) = AceLiveDhtNodeContact(
        nodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { nodeByte.toByte() }),
        endpoint = AceLiveTcpPeerEndpoint(host, port)
    )

    private class FakePersistence(
        private val loaded: List<AceDhtPersistedContact> = emptyList()
    ) : AceDhtRoutingPersistence {
        var saved: List<AceDhtPersistedContact> = emptyList()
            private set

        override fun load(): List<AceDhtPersistedContact> = loaded.map { item ->
            item.copy(
                contact = AceLiveDhtNodeContact(
                    nodeId = AceLiveDhtNodeId.fromBytes(item.contact.nodeId.toByteArray()),
                    endpoint = item.contact.endpoint.copy()
                )
            )
        }

        override fun save(contacts: List<AceDhtPersistedContact>) {
            saved = contacts.map { item ->
                item.copy(
                    contact = AceLiveDhtNodeContact(
                        nodeId = AceLiveDhtNodeId.fromBytes(item.contact.nodeId.toByteArray()),
                        endpoint = item.contact.endpoint.copy()
                    )
                )
            }
        }
    }
}
