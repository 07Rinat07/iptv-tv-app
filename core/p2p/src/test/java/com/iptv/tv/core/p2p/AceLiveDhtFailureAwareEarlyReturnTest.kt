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
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveDhtFailureAwareEarlyReturnTest {
    @Test
    fun `active connect failure keeps startup DHT walking past first rediscovered peer`() = runBlocking {
        val second = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val failedEndpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9001)
        val alternativeEndpoint = AceLiveTcpPeerEndpoint("127.0.0.1", 9002)
        val failedPeer = compactEndpoint(127, 0, 0, 1, failedEndpoint.port)
        val alternativePeer = compactEndpoint(127, 0, 0, 1, alternativeEndpoint.port)
        val secondNode = ByteArray(20) { 0x22 } +
            compactEndpoint(127, 0, 0, 1, second.localPort)
        val bootstrapThread = dhtServerThread(bootstrap) { _ ->
            response(
                transactionId = transactionId,
                remoteId = ByteArray(20) { 0x11 },
                values = listOf(failedPeer),
                nodes = secondNode
            )
        }
        val secondThread = dhtServerThread(second) { _ ->
            response(
                transactionId = transactionId,
                remoteId = ByteArray(20) { 0x22 },
                values = listOf(alternativePeer)
            )
        }
        val swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 })
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 5_000L
        )
        memory.recordFinalPreHandshakeFailure(
            swarmKey = swarmKey.toByteArray(),
            endpoint = failedEndpoint,
            nowMillis = 1_000L
        )

        try {
            val discovery = AceLiveDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 5_000,
                    searchBranching = 1,
                    returnAfterPeers = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) },
                connectFailureMemory = memory
            )
            val result = discovery.discover(
                AceLiveDhtDiscoveryRequest(
                    swarmKey = swarmKey,
                    bootstrapNodes = listOf(
                        AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)
                    ),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
                )
            )

            assertEquals(2, result.queriesSent)
            assertTrue(result.peers.contains(failedEndpoint))
            assertTrue(result.peers.contains(alternativeEndpoint))
        } finally {
            bootstrap.close()
            second.close()
            bootstrapThread.join(2_000)
            secondThread.join(2_000)
        }
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
