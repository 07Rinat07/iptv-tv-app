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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceContentIdDhtDiscoveryTest {
    @Test
    fun `iterative discovery keeps raw Content ID target and follows compact nodes`() = runBlocking {
        val second = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val contentIdBytes = ByteArray(20) { index -> (index + 1).toByte() }
        val contentId = AceContentIdDhtKey.fromBytes(contentIdBytes)
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val secondNode = ByteArray(20) { 0x22 } +
            compactEndpoint(127, 0, 0, 1, second.localPort)

        val bootstrapThread = dhtServerThread(bootstrap) { query ->
            assertArrayEquals(contentIdBytes, extractInfoHash(query))
            response(transactionId, ByteArray(20) { 0x11 }, nodes = secondNode)
        }
        val secondThread = dhtServerThread(second) { query ->
            assertArrayEquals(contentIdBytes, extractInfoHash(query))
            response(transactionId, ByteArray(20) { 0x22 }, values = listOf(peer))
        }

        try {
            val discovery = AceContentIdDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 5_000,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) }
            )
            val result = discovery.discover(
                AceContentIdDhtDiscoveryRequest(
                    contentId = contentId,
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
                )
            )

            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), result.peers)
            assertEquals(2, result.queriesSent)
            assertEquals(0, result.failedQueries)
        } finally {
            bootstrap.close()
            second.close()
            bootstrapThread.join(2_000)
            secondThread.join(2_000)
        }
    }

    @Test
    fun `loopback bootstrap remains rejected by default`() = runBlocking {
        val discovery = AceContentIdDhtDiscovery(
            policy = AceLiveDhtPolicy(requestTimeoutMillis = 100, discoveryBudgetMillis = 100),
            randomInt = { 0x1234 },
            addressResolver = { listOf(ipv4("127.0.0.1")) }
        )
        val contentId = AceContentIdDhtKey.fromBytes(ByteArray(20) { 0x55 })

        val result = discovery.discover(
            AceContentIdDhtDiscoveryRequest(
                contentId = contentId,
                bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", 6881)),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
            )
        )

        assertTrue(result.peers.isEmpty())
        assertEquals(0, result.queriesSent)
        assertEquals(1, result.rejectedEndpoints)
    }

    private fun extractInfoHash(query: ByteArray): ByteArray {
        val marker = "9:info_hash20:".toByteArray(StandardCharsets.US_ASCII)
        val start = query.indexOfSubArray(marker)
        require(start >= 0) { "get_peers query has no info_hash field" }
        val valueStart = start + marker.size
        return query.copyOfRange(valueStart, valueStart + 20)
    }

    private fun ByteArray.indexOfSubArray(needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > size) return -1
        for (start in 0..size - needle.size) {
            var matches = true
            for (index in needle.indices) {
                if (this[start + index] != needle[index]) {
                    matches = false
                    break
                }
            }
            if (matches) return start
        }
        return -1
    }

    private fun dhtServerThread(
        socket: DatagramSocket,
        responseFactory: (ByteArray) -> ByteArray
    ): Thread = thread(start = true, isDaemon = true) {
        try {
            val buffer = ByteArray(8 * 1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            val response = responseFactory(query)
            socket.send(DatagramPacket(response, response.size, packet.socketAddress))
        } catch (_: Exception) {
            // Socket closure is normal test cleanup.
        }
    }

    private fun response(
        transactionId: ByteArray,
        remoteId: ByteArray,
        values: List<ByteArray> = emptyList(),
        nodes: ByteArray? = null
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:rd2:id20:")
        write(remoteId)
        if (nodes != null) {
            writeAscii("5:nodes${nodes.size}:")
            write(nodes)
        }
        if (values.isNotEmpty()) {
            writeAscii("6:valuesl")
            values.forEach { compact ->
                writeAscii("${compact.size}:")
                write(compact)
            }
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
