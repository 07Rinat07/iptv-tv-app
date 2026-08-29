package com.iptv.tv.core.p2p

import java.net.Inet4Address
import java.net.InetAddress
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveLocalServiceDiscoveryTest {
    @Test
    fun `codec emits bounded bep14 announce and parses it back`() {
        val swarm = swarm(1)
        val encoded = AceLiveLsdCodec.encode(
            swarmKey = swarm,
            port = 8621,
            cookie = "0123456789abcdef"
        )
        val text = encoded.toString(StandardCharsets.US_ASCII)

        assertTrue(text.startsWith("BT-SEARCH * HTTP/1.1\r\n"))
        assertTrue(text.contains("Host: 239.192.152.143:6771\r\n"))
        assertTrue(text.contains("Port: 8621\r\n"))
        assertTrue(text.contains("Infohash: ${swarm.toHex()}\r\n"))
        assertTrue(text.contains("cookie: 0123456789abcdef\r\n"))
        assertTrue(text.endsWith("\r\n\r\n"))
        assertTrue(encoded.size <= AceLiveLsdCodec.MAX_WIRE_BYTES)

        val decoded = AceLiveLsdCodec.decode(encoded)
        assertEquals(8621, decoded?.port)
        assertEquals(setOf(swarm.toHex()), decoded?.infoHashes)
        assertEquals("0123456789abcdef", decoded?.cookie)
    }

    @Test
    fun `codec accepts multiple infohash headers and normalizes header names`() {
        val first = swarm(2)
        val second = swarm(3)
        val payload = (
            "BT-SEARCH * HTTP/1.1\r\n" +
                "hOsT: 239.192.152.143:6771\r\n" +
                "PORT: 5000\r\n" +
                "INFOHASH: ${first.toHex().uppercase()}\r\n" +
                "Infohash: ${second.toHex()}\r\n" +
                "X-Unknown: ignored\r\n" +
                "\r\n"
        ).toByteArray(StandardCharsets.US_ASCII)

        val decoded = AceLiveLsdCodec.decode(payload)

        assertEquals(5000, decoded?.port)
        assertEquals(setOf(first.toHex(), second.toHex()), decoded?.infoHashes)
        assertNull(decoded?.cookie)
    }

    @Test
    fun `codec rejects invalid request host port infohash and oversized datagram`() {
        val swarm = swarm(4)
        fun packet(lines: String): ByteArray = lines.toByteArray(StandardCharsets.US_ASCII)

        assertNull(
            AceLiveLsdCodec.decode(
                packet(
                    "GET * HTTP/1.1\r\nHost: 239.192.152.143:6771\r\n" +
                        "Port: 8621\r\nInfohash: ${swarm.toHex()}\r\n\r\n"
                )
            )
        )
        assertNull(
            AceLiveLsdCodec.decode(
                packet(
                    "BT-SEARCH * HTTP/1.1\r\nHost: 239.1.1.1:6771\r\n" +
                        "Port: 8621\r\nInfohash: ${swarm.toHex()}\r\n\r\n"
                )
            )
        )
        assertNull(
            AceLiveLsdCodec.decode(
                packet(
                    "BT-SEARCH * HTTP/1.1\r\nHost: 239.192.152.143:6771\r\n" +
                        "Port: 70000\r\nInfohash: ${swarm.toHex()}\r\n\r\n"
                )
            )
        )
        assertNull(
            AceLiveLsdCodec.decode(
                packet(
                    "BT-SEARCH * HTTP/1.1\r\nHost: 239.192.152.143:6771\r\n" +
                        "Port: 8621\r\nInfohash: not-a-hash\r\n\r\n"
                )
            )
        )
        assertNull(AceLiveLsdCodec.decode(ByteArray(AceLiveLsdCodec.MAX_WIRE_BYTES + 1) { 'A'.code.toByte() }))
    }

    @Test
    fun `peer cache stays bounded refreshes duplicates and expires stale hints`() {
        val cache = AceLiveLsdPeerCache(ttlMillis = 60_000L, maxPeers = 2)
        val first = AceLiveTcpPeerEndpoint("192.168.1.10", 8001)
        val second = AceLiveTcpPeerEndpoint("192.168.1.11", 8002)
        val overflow = AceLiveTcpPeerEndpoint("192.168.1.12", 8003)

        cache.record(first, 1_000L)
        cache.record(second, 1_001L)
        cache.record(overflow, 1_002L)
        assertEquals(listOf(first, second), cache.snapshot(1_002L))

        cache.record(first, 50_000L)
        assertEquals(listOf(first), cache.snapshot(61_002L))
        assertTrue(cache.snapshot(110_001L).isEmpty())
    }

    @Test
    fun `supplemental merge deduplicates lsd peer and preserves public provenance`() {
        val shared = AceLiveTcpPeerEndpoint("192.168.1.20", 8621)
        val localOnly = AceLiveTcpPeerEndpoint("192.168.1.21", 8622)
        val publicResult = AceLivePeerDiscoveryOrchestrationResult(
            peers = listOf(
                AceLiveDiscoveredPeer(
                    endpoint = shared,
                    sources = setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            ),
            dht = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.SUCCEEDED,
                returnedPeerCount = 1
            ),
            tracker = AceLivePeerDiscoverySourceSummary(
                status = AceLivePeerDiscoverySourceStatus.NOT_REQUESTED,
                returnedPeerCount = 0
            )
        )

        val merged = mergeAceLiveSupplementalPeers(
            result = publicResult,
            peers = listOf(shared, localOnly),
            source = AceLivePeerDiscoverySource.LOCAL_SERVICE_DISCOVERY,
            maxTotalPeers = 2
        )

        assertEquals(listOf(shared, localOnly), merged.tcpEndpoints())
        assertEquals(
            setOf(
                AceLivePeerDiscoverySource.MAINLINE_DHT,
                AceLivePeerDiscoverySource.LOCAL_SERVICE_DISCOVERY
            ),
            merged.peers.first().sources
        )
        assertEquals(
            setOf(AceLivePeerDiscoverySource.LOCAL_SERVICE_DISCOVERY),
            merged.peers.last().sources
        )
        assertEquals(publicResult.dht, merged.dht)
        assertEquals(publicResult.tracker, merged.tracker)
    }

    @Test
    fun `same prefix guard keeps candidates on selected lan`() {
        val local = ipv4("192.168.50.10")

        assertTrue(aceLiveLsdSameIpv4Prefix(local, ipv4("192.168.50.200"), 24))
        assertFalse(aceLiveLsdSameIpv4Prefix(local, ipv4("192.168.51.2"), 24))
        assertTrue(aceLiveLsdSameIpv4Prefix(ipv4("10.20.1.5"), ipv4("10.20.240.7"), 16))
        assertFalse(aceLiveLsdSameIpv4Prefix(ipv4("10.20.1.5"), ipv4("10.21.1.5"), 16))
    }

    private fun swarm(seed: Int): AceLiveSwarmKey = AceLiveSwarmKey.fromBytes(
        ByteArray(AceLiveSwarmKey.BYTES) { index -> (seed + index).toByte() }
    )

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address
}
