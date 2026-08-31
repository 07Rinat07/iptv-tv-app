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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceDhtQueryFailureMemoryIntegrationTest {
    @Test
    fun recentlyFailedLearnedNodeDoesNotConsumeNextWalkQuerySlot() = runBlocking {
        val dead = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val alternative = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val deadNodeId = ByteArray(20).also { it[it.lastIndex] = 0x01 }
        val alternativeNodeId = ByteArray(20).also { it[it.lastIndex] = 0x02 }
        val deadNode = deadNodeId + compactEndpoint(127, 0, 0, 1, dead.localPort)
        val alternativeNode = alternativeNodeId +
            compactEndpoint(127, 0, 0, 1, alternative.localPort)
        val peer = AceLiveTcpPeerEndpoint("127.0.0.1", 9101)
        val peerBytes = compactEndpoint(127, 0, 0, 1, peer.port)
        val bootstrapThread = dhtServerThread(bootstrap, requestCount = 2) { requestIndex, _ ->
            response(
                transactionId = transactionId,
                remoteId = ByteArray(20) { 0x11 },
                nodes = if (requestIndex == 0) deadNode else deadNode + alternativeNode
            )
        }
        val alternativeThread = dhtServerThread(alternative, requestCount = 1) { _, _ ->
            response(
                transactionId = transactionId,
                remoteId = alternativeNodeId,
                values = listOf(peerBytes)
            )
        }
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 20_000L
        )
        val discovery = AceLiveDhtDiscovery(
            policy = AceLiveDhtPolicy(
                requestTimeoutMillis = 150,
                discoveryBudgetMillis = 1_500,
                searchBranching = 1,
                maxQueries = 2,
                returnAfterPeers = 1,
                allowNonGlobalNodeAddresses = true,
                allowNonGlobalPeerAddresses = true
            ),
            randomInt = { 0x1234 },
            addressResolver = { listOf(ipv4("127.0.0.1")) },
            queryFailureMemory = memory
        )
        val request = AceLiveDhtDiscoveryRequest(
            swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20)),
            bootstrapNodes = listOf(
                AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)
            ),
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
        )

        try {
            val first = discovery.discover(request)

            assertEquals(2, first.queriesSent)
            assertEquals(1, first.failedQueries)
            assertTrue(first.peers.isEmpty())
            assertFalse(
                memory.isEligible(
                    AceLiveTcpPeerEndpoint("127.0.0.1", dead.localPort),
                    nowMillis = 1_000L
                )
            )

            val second = discovery.discover(request)

            assertEquals(2, second.queriesSent)
            assertEquals(0, second.failedQueries)
            assertTrue(second.peers.contains(peer))
        } finally {
            bootstrap.close()
            dead.close()
            alternative.close()
            bootstrapThread.join(2_000)
            alternativeThread.join(2_000)
        }
    }

    @Test
    fun bootstrapEndpointBypassesNegativeMemory() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val peer = AceLiveTcpPeerEndpoint("127.0.0.1", 9201)
        val bootstrapThread = dhtServerThread(bootstrap, requestCount = 1) { _, _ ->
            response(
                transactionId = transactionId,
                remoteId = ByteArray(20) { 0x21 },
                values = listOf(compactEndpoint(127, 0, 0, 1, peer.port))
            )
        }
        val memory = AceDhtQueryFailureMemory(
            clockMillis = { 1_000L },
            backoffMillis = 20_000L
        )
        memory.recordFailure(
            AceLiveTcpPeerEndpoint("127.0.0.1", bootstrap.localPort),
            nowMillis = 1_000L
        )

        try {
            val result = AceLiveDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 500,
                    discoveryBudgetMillis = 1_500,
                    searchBranching = 1,
                    maxQueries = 1,
                    returnAfterPeers = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) },
                queryFailureMemory = memory
            ).discover(
                AceLiveDhtDiscoveryRequest(
                    swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x33 }),
                    bootstrapNodes = listOf(
                        AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)
                    ),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
                )
            )

            assertEquals(1, result.queriesSent)
            assertEquals(0, result.failedQueries)
            assertTrue(result.peers.contains(peer))
        } finally {
            bootstrap.close()
            bootstrapThread.join(2_000)
        }
    }

    private fun dhtServerThread(
        socket: DatagramSocket,
        requestCount: Int,
        responseFactory: (requestIndex: Int, query: ByteArray) -> ByteArray
    ): Thread = thread(start = true, isDaemon = true) {
        try {
            repeat(requestCount) { requestIndex ->
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val response = responseFactory(requestIndex, query)
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
