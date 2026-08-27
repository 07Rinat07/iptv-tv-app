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

class AceLiveDhtConnectFailureCacheTest {
    @Test
    fun `cached batch containing only failed peer triggers fresh bounded DHT walk`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        var nowMillis = 1_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { nowMillis },
            backoffMillis = 5_000L
        )
        val swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 })
        val request = AceLiveDhtDiscoveryRequest(
            swarmKey = swarmKey,
            bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", bootstrap.localPort)),
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
        )
        val deadPeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8621)
        val alternativePeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8622)
        val discovery = discovery(
            memory = memory,
            nowMillis = { nowMillis },
            returnAfterPeers = 1
        )

        val firstServer = dhtServerThread(bootstrap, listOf(8621))
        try {
            val first = discovery.discover(request)
            firstServer.join(2_000)

            assertEquals(listOf(deadPeer), first.peers)
            assertFalse(first.cacheHit)

            memory.recordFinalPreHandshakeFailure(
                swarmKey = swarmKey.toByteArray(),
                endpoint = deadPeer,
                nowMillis = nowMillis
            )

            val secondServer = dhtServerThread(bootstrap, listOf(8622))
            val second = discovery.discover(request)
            secondServer.join(2_000)

            assertEquals(listOf(alternativePeer), second.peers)
            assertFalse(second.cacheHit)
            assertEquals(1, second.queriesSent)
        } finally {
            bootstrap.close()
        }
    }

    @Test
    fun `cached batch salvages eligible peers when one endpoint enters connect backoff`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val nowMillis = 20_000L
        val memory = AceLiveTcpConnectFailureMemory(
            clockMillis = { nowMillis },
            backoffMillis = 5_000L
        )
        val swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x56 })
        val request = AceLiveDhtDiscoveryRequest(
            swarmKey = swarmKey,
            bootstrapNodes = listOf(
                AceLiveDhtBootstrapNode("bootstrap.partial-cache.test", bootstrap.localPort)
            ),
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x45 })
        )
        val firstPeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8631)
        val failedPeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8632)
        val thirdPeer = AceLiveTcpPeerEndpoint("127.0.0.1", 8633)
        val discovery = discovery(
            memory = memory,
            nowMillis = { nowMillis },
            returnAfterPeers = null
        )
        val server = dhtServerThread(bootstrap, listOf(8631, 8632, 8633))

        try {
            val first = discovery.discover(request)
            server.join(2_000)
            assertEquals(listOf(firstPeer, failedPeer, thirdPeer), first.peers)
            assertFalse(first.cacheHit)

            memory.recordFinalPreHandshakeFailure(
                swarmKey = swarmKey.toByteArray(),
                endpoint = failedPeer,
                nowMillis = nowMillis
            )

            val reused = discovery.discover(request)

            assertTrue(reused.cacheHit)
            assertEquals(listOf(firstPeer, thirdPeer), reused.peers)
            assertEquals(0, reused.queriesSent)
            assertEquals(0, reused.failedQueries)
        } finally {
            bootstrap.close()
        }
    }

    private fun discovery(
        memory: AceLiveTcpConnectFailureMemory,
        nowMillis: () -> Long,
        returnAfterPeers: Int?
    ) = AceLiveDhtDiscovery(
        policy = AceLiveDhtPolicy(
            requestTimeoutMillis = 1_000,
            discoveryBudgetMillis = 3_000,
            returnAfterPeers = returnAfterPeers,
            allowNonGlobalNodeAddresses = true,
            allowNonGlobalPeerAddresses = true
        ),
        randomInt = { 0x1234 },
        addressResolver = { listOf(ipv4("127.0.0.1")) },
        reuseRecentResults = true,
        clockMillis = nowMillis,
        connectFailureMemory = memory
    )

    private fun dhtServerThread(socket: DatagramSocket, peerPorts: List<Int>): Thread =
        thread(start = true, isDaemon = true) {
            try {
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val response = response(
                    transactionId = byteArrayOf(0x12, 0x34),
                    remoteId = ByteArray(20) { 0x11 },
                    peers = peerPorts.map { port -> compactEndpoint(127, 0, 0, 1, port) }
                )
                socket.send(DatagramPacket(response, response.size, packet.socketAddress))
            } catch (_: Exception) {
                // Socket closure is normal test cleanup.
            }
        }

    private fun response(
        transactionId: ByteArray,
        remoteId: ByteArray,
        peers: List<ByteArray>
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:rd2:id20:")
        write(remoteId)
        writeAscii("6:valuesl")
        peers.forEach { peer ->
            writeAscii("${peer.size}:")
            write(peer)
        }
        writeAscii("ee1:t${transactionId.size}:")
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
