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

class AceLiveDhtWarmRoutingRegressionTest {
    @Test
    fun `second swarm reuses responsive DHT node learned by first swarm`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val learned = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val deadSecondBootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val learnedNodeId = ByteArray(20) { 0x22 }
        val learnedCompactNode = learnedNodeId + compactEndpoint(127, 0, 0, 1, learned.localPort)

        val bootstrapThread = dhtServerThread(bootstrap, responses = 1) { _, _ ->
            response(transactionId, ByteArray(20) { 0x11 }, nodes = learnedCompactNode)
        }
        val learnedThread = dhtServerThread(learned, responses = 2) { index, _ ->
            val peerPort = if (index == 0) 8621 else 8622
            response(
                transactionId,
                learnedNodeId,
                values = listOf(compactEndpoint(127, 0, 0, 1, peerPort))
            )
        }

        try {
            val discovery = AceLiveDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 250,
                    discoveryBudgetMillis = 1_000,
                    searchBranching = 1,
                    returnAfterPeers = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) }
            )

            val first = discovery.discover(
                request(
                    swarmByte = 0x55,
                    bootstrapHost = "first-bootstrap.test",
                    bootstrapPort = bootstrap.localPort
                )
            )
            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), first.peers)
            assertEquals(2, first.queriesSent)

            val second = discovery.discover(
                request(
                    swarmByte = 0x66,
                    bootstrapHost = "dead-second-bootstrap.test",
                    bootstrapPort = deadSecondBootstrap.localPort
                )
            )

            assertEquals(
                "a new swarm should reuse the previously responsive DHT node instead of starting cold",
                listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8622)),
                second.peers
            )
            assertEquals(
                "the warm node should satisfy the second lookup before the dead bootstrap is queried",
                1,
                second.queriesSent
            )
        } finally {
            bootstrap.close()
            learned.close()
            deadSecondBootstrap.close()
            bootstrapThread.join(2_000)
            learnedThread.join(2_000)
        }
    }

    private fun request(
        swarmByte: Int,
        bootstrapHost: String,
        bootstrapPort: Int
    ): AceLiveDhtDiscoveryRequest = AceLiveDhtDiscoveryRequest(
        swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { swarmByte.toByte() }),
        bootstrapNodes = listOf(AceLiveDhtBootstrapNode(bootstrapHost, bootstrapPort)),
        localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
    )

    private fun dhtServerThread(
        socket: DatagramSocket,
        responses: Int,
        responseFactory: (index: Int, query: ByteArray) -> ByteArray
    ): Thread = thread(start = true, isDaemon = true) {
        try {
            repeat(responses) { index ->
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val response = responseFactory(index, query)
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            }
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
