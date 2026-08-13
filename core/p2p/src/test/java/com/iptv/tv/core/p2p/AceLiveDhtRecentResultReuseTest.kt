package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveDhtRecentResultReuseTest {
    @Test
    fun `production reuse avoids an immediate duplicate DHT walk for the same swarm`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val transactionId = byteArrayOf(0x12, 0x34)
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                bootstrap.receive(packet)
                val response = response(
                    transactionId = transactionId,
                    remoteId = ByteArray(20) { 0x22 },
                    peer = peer
                )
                bootstrap.send(DatagramPacket(response, response.size, packet.socketAddress))
            } catch (_: Exception) {
                // Socket closure is normal test cleanup.
            }
        }

        try {
            val policy = AceLiveDhtPolicy(
                requestTimeoutMillis = 500,
                discoveryBudgetMillis = 1_000,
                allowNonGlobalNodeAddresses = true,
                allowNonGlobalPeerAddresses = true
            )
            val first = discovery(policy, nowMillis = 1_000L)
            val second = discovery(policy, nowMillis = 1_001L)
            val request = AceLiveDhtDiscoveryRequest(
                swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x66 }),
                bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.reuse.test", bootstrap.localPort)),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
            )

            val firstResult = first.discover(request)
            val reusedResult = second.discover(request)

            val expectedPeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8621)
            assertEquals(listOf(expectedPeer), firstResult.peers)
            assertEquals(firstResult, reusedResult)
        } finally {
            bootstrap.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `production reuse never caches an empty DHT walk`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val transactionId = byteArrayOf(0x12, 0x34)
        val serverThread = thread(start = true, isDaemon = true) {
            try {
                repeat(2) { queryIndex ->
                    val buffer = ByteArray(8 * 1024)
                    val packet = DatagramPacket(buffer, buffer.size)
                    bootstrap.receive(packet)
                    val response = response(
                        transactionId = transactionId,
                        remoteId = ByteArray(20) { 0x22 },
                        peer = peer.takeIf { queryIndex == 1 }
                    )
                    bootstrap.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
            } catch (_: Exception) {
                // Socket closure is normal test cleanup.
            }
        }

        try {
            val policy = AceLiveDhtPolicy(
                requestTimeoutMillis = 500,
                discoveryBudgetMillis = 1_000,
                allowNonGlobalNodeAddresses = true,
                allowNonGlobalPeerAddresses = true
            )
            val first = discovery(policy, nowMillis = 2_000L)
            val second = discovery(policy, nowMillis = 2_001L)
            val request = AceLiveDhtDiscoveryRequest(
                swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x67 }),
                bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.empty-reuse.test", bootstrap.localPort)),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
            )

            assertEquals(emptyList<AceLiveTcpPeerEndpoint>(), first.discover(request).peers)
            assertEquals(
                listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)),
                second.discover(request).peers
            )
        } finally {
            bootstrap.close()
            serverThread.join(2_000)
        }
    }

    private fun discovery(policy: AceLiveDhtPolicy, nowMillis: Long) = AceLiveDhtDiscovery(
        policy = policy,
        randomInt = { 0x1234 },
        addressResolver = { listOf(ipv4("127.0.0.1")) },
        reuseRecentResults = true,
        clockMillis = { nowMillis }
    )

    private fun response(
        transactionId: ByteArray,
        remoteId: ByteArray,
        peer: ByteArray?
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:rd2:id20:")
        write(remoteId)
        if (peer != null) {
            writeAscii("6:valuesl${peer.size}:")
            write(peer)
            writeAscii("e")
        }
        writeAscii("e1:t${transactionId.size}:")
        write(transactionId)
        writeAscii("1:y1:re")
    }.toByteArray()

    private fun compactEndpoint(a: Int, b: Int, c: Int, d: Int, port: Int): ByteArray =
        byteArrayOf(
            a.toByte(), b.toByte(), c.toByte(), d.toByte(),
            (port ushr 8).toByte(), port.toByte()
        )

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
