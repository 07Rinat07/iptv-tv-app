package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveDhtSharedRoutingMemoryTest {
    @Test
    fun `new discovery instance reuses verified routing while bootstrap dns is blocked`() = runBlocking {
        val firstBootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val learnedNode = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val blockedResolverEntered = CountDownLatch(1)
        val releaseBlockedResolver = CountDownLatch(1)
        val warmQueryOverlappedBlockedResolver = CountDownLatch(1)
        val transactionId = byteArrayOf(0x12, 0x34)
        val learnedNodeId = ByteArray(20) { 0x22 }
        val learnedCompactNode = learnedNodeId +
            compactEndpoint(127, 0, 0, 1, learnedNode.localPort)

        val bootstrapThread = dhtServerThread(firstBootstrap, responses = 1) { _, _ ->
            response(transactionId, ByteArray(20) { 0x11 }, nodes = learnedCompactNode)
        }
        val learnedThread = dhtServerThread(learnedNode, responses = 3) { index, _ ->
            if (index == 1 && blockedResolverEntered.await(500L, TimeUnit.MILLISECONDS)) {
                warmQueryOverlappedBlockedResolver.countDown()
            }
            response(
                transactionId = transactionId,
                remoteId = learnedNodeId,
                values = listOf(
                    compactEndpoint(127, 0, 0, 1, 8621 + index)
                )
            )
        }

        try {
            val memory = AceDhtRoutingMemory()
            val policy = AceLiveDhtPolicy(
                requestTimeoutMillis = 750,
                discoveryBudgetMillis = 2_000,
                searchBranching = 1,
                returnAfterPeers = 1,
                allowNonGlobalNodeAddresses = true,
                allowNonGlobalPeerAddresses = true
            )
            val first = discovery(
                policy = policy,
                memory = memory,
                resolver = { listOf(ipv4("127.0.0.1")) }
            ).discover(
                request(
                    swarmByte = 0x55,
                    bootstrapHost = "first-bootstrap.test",
                    bootstrapPort = firstBootstrap.localPort
                )
            )

            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8621)), first.peers)
            assertEquals(0, first.warmRoutingSeedsUsed)

            val secondDiscovery = discovery(
                policy = policy,
                memory = memory,
                resolver = {
                    blockedResolverEntered.countDown()
                    releaseBlockedResolver.await()
                    emptyList()
                }
            )
            val second = withTimeout(2_500L) {
                secondDiscovery.discover(
                    request(
                        swarmByte = 0x66,
                        bootstrapHost = "blocked-bootstrap.test",
                        bootstrapPort = 6881
                    )
                )
            }

            assertEquals(0L, blockedResolverEntered.count)
            assertEquals(0L, warmQueryOverlappedBlockedResolver.count)
            assertEquals(listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8622)), second.peers)
            assertEquals(1, second.queriesSent)
            assertEquals(1, second.warmRoutingSeedsUsed)

            val contentResult = withTimeout(2_500L) {
                AceContentIdDhtDiscovery(
                    policy = policy,
                    randomInt = { 0x1234 },
                    addressResolver = {
                        blockedResolverEntered.countDown()
                        releaseBlockedResolver.await()
                        emptyList()
                    },
                    routingMemory = memory
                ).discover(
                    AceContentIdDhtDiscoveryRequest(
                        contentId = AceContentIdDhtKey.fromBytes(ByteArray(20) { 0x77 }),
                        bootstrapNodes = listOf(
                            AceLiveDhtBootstrapNode("blocked-content-bootstrap.test", 6881)
                        ),
                        localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x44 })
                    )
                )
            }

            assertEquals(
                listOf(AceLiveTcpPeerEndpoint("127.0.0.1", 8623)),
                contentResult.peers
            )
            assertEquals(1, contentResult.queriesSent)
            assertEquals(1, contentResult.warmRoutingSeedsUsed)
        } finally {
            releaseBlockedResolver.countDown()
            firstBootstrap.close()
            learnedNode.close()
            bootstrapThread.join(2_000)
            learnedThread.join(2_000)
        }
    }

    @Test
    fun `production branching reserves one first-wave bootstrap lane`() {
        assertEquals(1, aceDhtWarmRoutingSeedLimit(searchBranching = 1))
        assertEquals(3, aceDhtWarmRoutingSeedLimit(searchBranching = 4))
        assertEquals(4, aceDhtWarmRoutingSeedLimit(searchBranching = 8))
    }

    private fun discovery(
        policy: AceLiveDhtPolicy,
        memory: AceDhtRoutingMemory,
        resolver: (String) -> List<Inet4Address>
    ) = AceLiveDhtDiscovery(
        policy = policy,
        randomInt = { 0x1234 },
        addressResolver = resolver,
        routingMemory = memory
    )

    private fun request(
        swarmByte: Int,
        bootstrapHost: String,
        bootstrapPort: Int
    ) = AceLiveDhtDiscoveryRequest(
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
        nodes?.let { compactNodes ->
            writeAscii("5:nodes${compactNodes.size}:")
            write(compactNodes)
        }
        if (values.isNotEmpty()) {
            writeAscii("6:valuesl")
            values.forEach { peer ->
                writeAscii("${peer.size}:")
                write(peer)
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
