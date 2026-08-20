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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceDhtBootstrapSchedulingTest {
    @Test
    fun `slow bootstrap keeps one query token while warm nodes respond`() = runBlocking {
        val warm = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrap = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val bootstrapQueried = CountDownLatch(1)
        val releaseDns = CountDownLatch(1)
        val warmResponseSent = CountDownLatch(1)
        val warmNodeId = ByteArray(20) { 0x21 }
        val learnedNode = ByteArray(20) { 0x31 } +
            compactEndpoint(127, 0, 0, 1, 65001)
        val warmThread = serverThread(
            warm,
            afterResponse = warmResponseSent::countDown
        ) { response(remoteId = warmNodeId, nodes = learnedNode) }
        val bootstrapThread = serverThread(bootstrap, onQuery = bootstrapQueried::countDown) {
            response(remoteId = ByteArray(20) { 0x41 })
        }
        val dnsReleaseThread = thread(start = true, isDaemon = true) {
            warmResponseSent.await()
            Thread.sleep(100L)
            releaseDns.countDown()
        }

        try {
            val memory = AceDhtRoutingMemory().apply {
                remember(
                    AceLiveDhtNodeContact(
                        nodeId = AceLiveDhtNodeId.fromBytes(warmNodeId),
                        endpoint = AceLiveTcpPeerEndpoint("127.0.0.1", warm.localPort)
                    )
                )
            }
            val result = discovery(
                policy = policy(searchBranching = 2, maxQueries = 2),
                memory = memory,
                resolver = {
                    releaseDns.await()
                    listOf(ipv4("127.0.0.1"))
                }
            ).discover(request(listOf(AceLiveDhtBootstrapNode("slow.test", bootstrap.localPort))))

            assertEquals(2, result.queriesSent)
            assertTrue(bootstrapQueried.await(100L, TimeUnit.MILLISECONDS))
        } finally {
            releaseDns.countDown()
            warm.close()
            bootstrap.close()
            warmThread.join(2_000L)
            bootstrapThread.join(2_000L)
            dnsReleaseThread.join(2_000L)
        }
    }

    @Test
    fun `first bootstrap wave uses one address per hostname before extras`() = runBlocking {
        val silentFirstHost = DatagramSocket(InetSocketAddress("0.0.0.0", 0))
        val responsiveSecondHost = DatagramSocket(InetSocketAddress("127.0.0.1", 0))
        val firstHostQueried = CountDownLatch(1)
        val secondHostQueried = CountDownLatch(1)
        val firstHostThread = thread(start = true, isDaemon = true) {
            try {
                val packet = DatagramPacket(ByteArray(8 * 1024), 8 * 1024)
                silentFirstHost.receive(packet)
                firstHostQueried.countDown()
                // Intentionally do not answer the first hostname.
            } catch (_: Exception) {
                // Socket closure is normal test cleanup.
            }
        }
        val secondHostThread = serverThread(
            responsiveSecondHost,
            onQuery = secondHostQueried::countDown
        ) {
            response(remoteId = ByteArray(20) { 0x51 })
        }

        val lookup = async(Dispatchers.Default) {
            discovery(
                policy = policy(searchBranching = 4, maxQueries = 16, requestTimeoutMillis = 600),
                memory = AceDhtRoutingMemory(),
                resolver = { host ->
                    if (host == "first.test") {
                        listOf(
                            ipv4("127.0.0.1"),
                            ipv4("127.0.0.2"),
                            ipv4("127.0.0.3"),
                            ipv4("127.0.0.4")
                        )
                    } else {
                        firstHostQueried.await()
                        listOf(ipv4("127.0.0.1"))
                    }
                }
            ).discover(
                request(
                    listOf(
                        AceLiveDhtBootstrapNode("first.test", silentFirstHost.localPort),
                        AceLiveDhtBootstrapNode("second.test", responsiveSecondHost.localPort)
                    )
                )
            )
        }

        try {
            assertTrue(
                "second bootstrap hostname should be queried before the first request times out",
                secondHostQueried.await(300L, TimeUnit.MILLISECONDS)
            )
        } finally {
            lookup.cancelAndJoin()
            silentFirstHost.close()
            responsiveSecondHost.close()
            firstHostThread.join(2_000L)
            secondHostThread.join(2_000L)
        }
    }

    @Test
    fun `blocking resolver work is globally capped and cancellation stays prompt`() = runBlocking {
        val releaseResolvers = CountDownLatch(1)
        val fourResolversStarted = CountDownLatch(ACE_DHT_GLOBAL_RESOLVER_WORKERS)
        val activeResolvers = AtomicInteger(0)
        val startedResolvers = AtomicInteger(0)
        val maximumActiveResolvers = AtomicInteger(0)
        val blockingResolver: (String) -> List<Inet4Address> = {
            val active = activeResolvers.incrementAndGet()
            startedResolvers.incrementAndGet()
            maximumActiveResolvers.accumulateAndGet(active, ::maxOf)
            fourResolversStarted.countDown()
            try {
                while (releaseResolvers.count > 0L) {
                    try {
                        releaseResolvers.await()
                    } catch (_: InterruptedException) {
                        // Simulate a platform resolver that ignores interruption.
                    }
                }
                emptyList()
            } finally {
                activeResolvers.decrementAndGet()
            }
        }
        val policy = policy(searchBranching = 4, maxQueries = 16, requestTimeoutMillis = 500)
        val first = async(Dispatchers.Default) {
            discovery(policy, AceDhtRoutingMemory(), blockingResolver).discover(
                request((1..4).map { AceLiveDhtBootstrapNode("first-$it.test", 6800 + it) })
            )
        }
        val second = async(Dispatchers.Default) {
            discovery(policy, AceDhtRoutingMemory(), blockingResolver).discover(
                request((1..4).map { AceLiveDhtBootstrapNode("second-$it.test", 6900 + it) })
            )
        }

        try {
            assertTrue(fourResolversStarted.await(1L, TimeUnit.SECONDS))
            Thread.sleep(100L)
            assertEquals(ACE_DHT_GLOBAL_RESOLVER_WORKERS, maximumActiveResolvers.get())
            assertEquals(ACE_DHT_GLOBAL_RESOLVER_WORKERS, startedResolvers.get())
            withTimeout(500L) {
                first.cancelAndJoin()
                second.cancelAndJoin()
            }
            assertEquals(ACE_DHT_GLOBAL_RESOLVER_WORKERS, startedResolvers.get())
        } finally {
            first.cancel()
            second.cancel()
            releaseResolvers.countDown()
            first.cancelAndJoin()
            second.cancelAndJoin()
        }
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

    private fun policy(
        searchBranching: Int,
        maxQueries: Int,
        requestTimeoutMillis: Int = 300
    ) = AceLiveDhtPolicy(
        requestTimeoutMillis = requestTimeoutMillis,
        discoveryBudgetMillis = 1_500L,
        searchBranching = searchBranching,
        maxBootstrapNodes = 8,
        maxQueries = maxQueries,
        allowNonGlobalNodeAddresses = true,
        allowNonGlobalPeerAddresses = true
    )

    private fun request(bootstraps: List<AceLiveDhtBootstrapNode>) =
        AceLiveDhtDiscoveryRequest(
            swarmKey = AceLiveSwarmKey.fromBytes(ByteArray(20) { 0x61 }),
            bootstrapNodes = bootstraps,
            localNodeId = AceLiveDhtNodeId.fromBytes(ByteArray(20) { 0x71 })
        )

    private fun serverThread(
        socket: DatagramSocket,
        onQuery: () -> Unit = {},
        afterResponse: () -> Unit = {},
        responseFactory: () -> ByteArray
    ): Thread = thread(start = true, isDaemon = true) {
        try {
            val buffer = ByteArray(8 * 1024)
            val packet = DatagramPacket(buffer, buffer.size)
            socket.receive(packet)
            onQuery()
            val bytes = responseFactory()
            socket.send(DatagramPacket(bytes, bytes.size, packet.socketAddress))
            afterResponse()
        } catch (_: Exception) {
            // Socket closure is normal test cleanup.
        }
    }

    private fun response(remoteId: ByteArray, nodes: ByteArray? = null): ByteArray =
        ByteArrayOutputStream().apply {
            writeAscii("d1:rd2:id20:")
            write(remoteId)
            nodes?.let { compactNodes ->
                writeAscii("5:nodes${compactNodes.size}:")
                write(compactNodes)
            }
            writeAscii("e1:t2:")
            write(byteArrayOf(0x12, 0x34))
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
