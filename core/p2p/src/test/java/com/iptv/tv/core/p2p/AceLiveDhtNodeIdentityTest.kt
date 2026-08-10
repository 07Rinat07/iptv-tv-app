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

class AceLiveDhtNodeIdentityTest {
    @Test
    fun `discovered contact is rejected when endpoint responds with another node id`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val second = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val advertisedId = ByteArray(20) { 0x22 }
        val wrongResponseId = ByteArray(20) { 0x33 }
        val contact = advertisedId + compactEndpoint(127, 0, 0, 1, second.localPort)

        val bootstrapThread = server(bootstrap) { response(transactionId, ByteArray(20) { 1 }, nodes = contact) }
        val secondThread = server(second) {
            response(
                transactionId,
                wrongResponseId,
                values = listOf(compactEndpoint(127, 0, 0, 1, 8621))
            )
        }

        try {
            val discovery = AceLiveDhtDiscovery(
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
                AceLiveDhtDiscoveryRequest(
                    swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
                )
            )

            assertTrue(result.peers.isEmpty())
            assertEquals(2, result.queriesSent)
            assertEquals(1, result.failedQueries)
        } finally {
            bootstrap.close()
            second.close()
            bootstrapThread.join(2_000)
            secondThread.join(2_000)
        }
    }

    private fun server(socket: DatagramSocket, responseFactory: () -> ByteArray): Thread =
        thread(start = true, isDaemon = true) {
            try {
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = responseFactory()
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
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte(), (port ushr 8).toByte(), port.toByte())

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private fun ByteArrayOutputStream.writeAscii(value: String) {
        write(value.toByteArray(StandardCharsets.US_ASCII))
    }
}
