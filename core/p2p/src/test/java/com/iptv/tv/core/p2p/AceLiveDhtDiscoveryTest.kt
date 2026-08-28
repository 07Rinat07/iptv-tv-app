package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import kotlin.concurrent.thread
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveDhtDiscoveryTest {
    @Test
    fun `iterative discovery follows compact node and returns peer`() = runBlocking {
        val second = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val secondNode = ByteArray(20) { 0x22 } +
            compactEndpoint(127, 0, 0, 1, second.localPort)

        val bootstrapThread = dhtServerThread(bootstrap) { query ->
            assertTrue(String(query, StandardCharsets.ISO_8859_1).contains("get_peers"))
            response(transactionId, ByteArray(20) { 0x11 }, nodes = secondNode)
        }
        val secondThread = dhtServerThread(second) { _ ->
            response(transactionId, ByteArray(20) { 0x22 }, values = listOf(peer))
        }

        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 5_000,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(
                request(bootstrap.localPort)
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
    fun `full live lookup announces owned TCP listener to token bearing node`() = runBlocking {
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val token = "write-token".toByteArray(StandardCharsets.US_ASCII)
        val remoteId = ByteArray(20) { 0x31 }
        val queries = mutableListOf<String>()
        val serverThread = dhtServerThread(server, requestCount = 2) { query, requestIndex ->
            queries += String(query, StandardCharsets.ISO_8859_1)
            if (requestIndex == 0) {
                response(transactionId, remoteId, token = token)
            } else {
                response(transactionId, remoteId)
            }
        }

        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    maxQueries = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(
                AceLiveDhtDiscoveryRequest(
                    swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", server.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 }),
                    announcePort = 45678
                )
            )

            assertEquals(1, result.queriesSent)
            assertEquals(1, result.announcesSent)
            assertEquals(1, result.announcesSucceeded)
            assertEquals(2, queries.size)
            assertTrue(queries[0].contains("get_peers"))
            assertTrue(queries[1].contains("announce_peer"))
            assertTrue(queries[1].contains("4:porti45678e"))
            assertTrue(queries[1].contains("5:token${token.size}:write-token"))
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `failed DHT announce does not discard discovered peer`() = runBlocking {
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val remoteId = ByteArray(20) { 0x31 }
        val wrongAnnounceId = ByteArray(20) { 0x32 }
        val serverThread = dhtServerThread(server, requestCount = 2) { _, requestIndex ->
            if (requestIndex == 0) {
                response(
                    transactionId,
                    remoteId,
                    values = listOf(peer),
                    token = "write-token".toByteArray(StandardCharsets.US_ASCII)
                )
            } else {
                response(transactionId, wrongAnnounceId)
            }
        }

        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    maxQueries = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(
                AceLiveDhtDiscoveryRequest(
                    swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", server.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 }),
                    announcePort = 45678
                )
            )

            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), result.peers)
            assertEquals(1, result.announcesSent)
            assertEquals(0, result.announcesSucceeded)
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `startup early return never waits for DHT announce`() = runBlocking {
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val serverThread = dhtServerThread(server) { _ ->
            response(
                transactionId,
                ByteArray(20) { 0x31 },
                values = listOf(peer),
                token = "write-token".toByteArray(StandardCharsets.US_ASCII)
            )
        }

        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    maxQueries = 1,
                    returnAfterPeers = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(
                AceLiveDhtDiscoveryRequest(
                    swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", server.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 }),
                    announcePort = 45678
                )
            )

            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), result.peers)
            assertEquals(0, result.announcesSent)
            assertEquals(0, result.announcesSucceeded)
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `loopback bootstrap is rejected by default`() = runBlocking {
        val discovery = AceLiveDhtDiscovery(
            policy = AceLiveDhtPolicy(requestTimeoutMillis = 100, discoveryBudgetMillis = 100),
            randomInt = { 0x1234 },
            addressResolver = { listOf(ipv4("127.0.0.1")) }
        )

        val result = discovery.discover(request(6881))

        assertTrue(result.peers.isEmpty())
        assertEquals(0, result.queriesSent)
        assertEquals(1, result.rejectedEndpoints)
    }

    @Test
    fun `non global returned peer is rejected independently from node policy`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val serverThread = dhtServerThread(bootstrap) { _ ->
            response(
                transactionId,
                ByteArray(20) { 1 },
                values = listOf(compactEndpoint(127, 0, 0, 1, 8621))
            )
        }
        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = false
                )
            )
            val result = discovery.discover(request(bootstrap.localPort))

            assertTrue(result.peers.isEmpty())
            assertEquals(1, result.queriesSent)
            assertEquals(1, result.rejectedEndpoints)
        } finally {
            bootstrap.close()
            serverThread.join(2_000)
        }
    }

    @Test
    fun `query cap prevents unbounded iterative walk`() = runBlocking {
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val second = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val secondNode = ByteArray(20) { 2 } + compactEndpoint(127, 0, 0, 1, second.localPort)
        val bootstrapThread = dhtServerThread(bootstrap) { _ ->
            response(transactionId, ByteArray(20) { 1 }, nodes = secondNode)
        }
        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    maxQueries = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(request(bootstrap.localPort))

            assertTrue(result.peers.isEmpty())
            assertEquals(1, result.queriesSent)
        } finally {
            bootstrap.close()
            second.close()
            bootstrapThread.join(2_000)
        }
    }

    @Test
    fun `timed out node is isolated as failed query`() = runBlocking {
        val silent = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        try {
            val discovery = localDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 100,
                    discoveryBudgetMillis = 500,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                )
            )
            val result = discovery.discover(request(silent.localPort))

            assertTrue(result.peers.isEmpty())
            assertEquals(1, result.queriesSent)
            assertEquals(1, result.failedQueries)
        } finally {
            silent.close()
        }
    }

    @Test
    fun `silent DHT branch does not block a responsive branch`() = runBlocking {
        val sockets = listOf(
            DatagramSocket(InetSocketAddress("127.0.0.1", 0)),
            DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        ).sortedBy { socket -> "127.0.0.1:${socket.localPort}" }
        val silent = sockets[0]
        val responsive = sockets[1]
        val transactionId = byteArrayOf(0x12, 0x34)
        val peer = compactEndpoint(127, 0, 0, 1, 8621)
        val responsiveThread = dhtServerThread(responsive) { _ ->
            response(transactionId, ByteArray(20) { 0x22 }, values = listOf(peer))
        }

        try {
            val discovery = AceLiveDhtDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 5_000,
                    discoveryBudgetMillis = 5_500,
                    searchBranching = 2,
                    returnAfterPeers = 1,
                    allowNonGlobalNodeAddresses = true,
                    allowNonGlobalPeerAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) }
            )
            val request = AceLiveDhtDiscoveryRequest(
                swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
                bootstrapNodes = listOf(
                    AceLiveDhtBootstrapNode("silent.test", silent.localPort),
                    AceLiveDhtBootstrapNode("responsive.test", responsive.localPort)
                ),
                localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
            )

            lateinit var result: AceLiveDhtDiscoveryResult
            val elapsedMillis = measureTimeMillis {
                result = discovery.discover(request)
            }

            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), result.peers)
            assertEquals(2, result.queriesSent)
            assertTrue(
                "first DHT peer should return before the 5s silent branch, took ${elapsedMillis}ms",
                elapsedMillis < 3_000
            )
        } finally {
            silent.close()
            responsive.close()
            responsiveThread.join(2_000)
        }
    }

    @Test
    fun `BEP42 external address is observed before strict error response is rejected`() = runBlocking {
        val server = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val transactionId = byteArrayOf(0x12, 0x34)
        val observations = mutableListOf<Pair<String, String>>()
        val serverThread = dhtServerThread(server) { _ ->
            errorResponseWithObservedIp(
                transactionId = transactionId,
                observed = compactEndpoint(8, 8, 8, 8, 49152)
            )
        }

        try {
            val walker = AceDhtIterativeDiscovery(
                policy = AceLiveDhtPolicy(
                    requestTimeoutMillis = 1_000,
                    discoveryBudgetMillis = 2_000,
                    maxQueries = 1,
                    allowNonGlobalNodeAddresses = true
                ),
                randomInt = { 0x1234 },
                addressResolver = { listOf(ipv4("127.0.0.1")) },
                externalAddressObserver = AceDhtExternalAddressObserver { observedHost, responderHost ->
                    observations += observedHost to responderHost
                }
            )
            val swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 })
            val outcome = walker.discover(
                AceDhtLookupRequest(
                    targetBytes = swarmKey.toByteArray(),
                    bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", server.localPort)),
                    localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 }),
                    encodeGetPeersQuery = { queryTransactionId, nodeId ->
                        AceLiveDhtCodec.encodeGetPeersQuery(
                            transactionId = queryTransactionId,
                            nodeId = nodeId,
                            swarmKey = swarmKey
                        )
                    }
                )
            )

            assertEquals(1, outcome.queriesSent)
            assertEquals(1, outcome.failedQueries)
            assertEquals(listOf("8.8.8.8" to "127.0.0.1"), observations)
        } finally {
            server.close()
            serverThread.join(2_000)
        }
    }

    private fun localDiscovery(policy: AceLiveDhtPolicy): AceLiveDhtDiscovery =
        AceLiveDhtDiscovery(
            policy = policy,
            randomInt = { 0x1234 },
            addressResolver = { listOf(ipv4("127.0.0.1")) }
        )

    private fun request(port: Int): AceLiveDhtDiscoveryRequest =
        AceLiveDhtDiscoveryRequest(
            swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x55 }),
            bootstrapNodes = listOf(AceLiveDhtBootstrapNode("bootstrap.test", port)),
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
        )

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

    private fun dhtServerThread(
        socket: DatagramSocket,
        requestCount: Int,
        responseFactory: (ByteArray, Int) -> ByteArray
    ): Thread = thread(start = true, isDaemon = true) {
        try {
            repeat(requestCount) { requestIndex ->
                val buffer = ByteArray(8 * 1024)
                val packet = DatagramPacket(buffer, buffer.size)
                socket.receive(packet)
                val query = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                val response = responseFactory(query, requestIndex)
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
        nodes: ByteArray? = null,
        token: ByteArray? = null
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:rd2:id20:")
        write(remoteId)
        if (nodes != null) {
            writeAscii("5:nodes${nodes.size}:")
            write(nodes)
        }
        if (token != null) {
            writeAscii("5:token${token.size}:")
            write(token)
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

    private fun errorResponseWithObservedIp(
        transactionId: ByteArray,
        observed: ByteArray
    ): ByteArray = ByteArrayOutputStream().apply {
        writeAscii("d1:eli203e15:invalid node ide2:ip${observed.size}:")
        write(observed)
        writeAscii("1:t${transactionId.size}:")
        write(transactionId)
        writeAscii("1:y1:ee")
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
