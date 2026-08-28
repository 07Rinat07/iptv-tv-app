package com.iptv.tv.core.p2p

import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveHappyEyeballsSocketConnectorTest {
    @Test
    fun `resolved addresses alternate the resolver preferred family`() {
        val ipv6First = ipv6(1)
        val ipv6Second = ipv6(2)
        val ipv4First = ipv4(1)
        val ipv4Second = ipv4(2)

        assertEquals(
            listOf(ipv6First, ipv4First, ipv6Second, ipv4Second),
            aceLiveHappyEyeballsOrder(
                listOf(ipv6First, ipv6Second, ipv4First, ipv4Second)
            )
        )
    }

    @Test
    fun `remembered family overrides resolver order only while still available`() {
        val ipv6 = ipv6(2)
        val ipv4 = ipv4(2)

        assertEquals(
            listOf(ipv4, ipv6),
            aceLiveHappyEyeballsOrder(
                addresses = listOf(ipv6, ipv4),
                preferredIpv6 = false
            )
        )
        assertEquals(
            listOf(ipv6),
            aceLiveHappyEyeballsOrder(
                addresses = listOf(ipv6),
                preferredIpv6 = false
            )
        )
    }

    @Test
    fun `alternate address races after fallback delay and cancels hanging preferred attempt`() {
        runBlocking {
            val preferred = ipv6(3)
            val alternate = ipv4(3)
            val preferredStarted = CompletableDeferred<Unit>()
            val alternateStarted = CompletableDeferred<Unit>()
            val preferredCancelled = CompletableDeferred<Unit>()
            val connector = AceLiveHappyEyeballsSocketConnector(
                ioDispatcher = Dispatchers.Default,
                addressResolver = { listOf(preferred, alternate) },
                socketConnector = { address, _ ->
                    when (address.address) {
                        preferred -> {
                            preferredStarted.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                preferredCancelled.complete(Unit)
                            }
                        }

                        alternate -> {
                            alternateStarted.complete(Unit)
                            Socket()
                        }

                        else -> error("unexpected address $address")
                    }
                },
                familyMemory = AceLiveHappyEyeballsAddressFamilyMemory(),
                fallbackDelayMillis = 100L
            )

            val connection = async {
                connector.connect(
                    endpoint = AceLiveTcpPeerEndpoint("peer.test", 9_300),
                    policy = AceLiveTcpConnectionPolicy()
                )
            }

            withTimeout(500L) { preferredStarted.await() }
            delay(20L)
            assertFalse(alternateStarted.isCompleted)

            withTimeout(500L) { alternateStarted.await() }
            val socket = withTimeout(500L) { connection.await() }
            withTimeout(500L) { preferredCancelled.await() }

            assertTrue(preferredCancelled.isCompleted)
            socket.close()
        }
    }

    @Test
    fun `fast preferred failure releases alternate without waiting full fallback delay`() {
        runBlocking {
            val preferred = ipv6(4)
            val alternate = ipv4(4)
            val preferredStarted = CompletableDeferred<Unit>()
            val alternateStarted = CompletableDeferred<Unit>()
            val connector = AceLiveHappyEyeballsSocketConnector(
                ioDispatcher = Dispatchers.Default,
                addressResolver = { listOf(preferred, alternate) },
                socketConnector = { address, _ ->
                    when (address.address) {
                        preferred -> {
                            preferredStarted.complete(Unit)
                            throw IOException("preferred path unavailable")
                        }

                        alternate -> {
                            alternateStarted.complete(Unit)
                            Socket()
                        }

                        else -> error("unexpected address $address")
                    }
                },
                familyMemory = AceLiveHappyEyeballsAddressFamilyMemory(),
                fallbackDelayMillis = 5_000L
            )

            val connection = async {
                connector.connect(
                    endpoint = AceLiveTcpPeerEndpoint("peer.test", 9_301),
                    policy = AceLiveTcpConnectionPolicy()
                )
            }

            withTimeout(500L) { preferredStarted.await() }
            withTimeout(500L) { alternateStarted.await() }
            val socket = withTimeout(500L) { connection.await() }

            assertTrue(alternateStarted.isCompleted)
            socket.close()
        }
    }

    @Test
    fun `successful alternate family is preferred on next connect`() {
        runBlocking {
            val preferred = ipv6(5)
            val alternate = ipv4(5)
            val failPreferredOnce = AtomicBoolean(true)
            val started = CopyOnWriteArrayList<InetAddress>()
            val memory = AceLiveHappyEyeballsAddressFamilyMemory()
            val connector = AceLiveHappyEyeballsSocketConnector(
                ioDispatcher = Dispatchers.Default,
                addressResolver = { listOf(preferred, alternate) },
                socketConnector = { address, _ ->
                    started += address.address
                    if (
                        address.address == preferred &&
                        failPreferredOnce.compareAndSet(true, false)
                    ) {
                        throw IOException("preferred path unavailable")
                    }
                    Socket()
                },
                familyMemory = memory,
                fallbackDelayMillis = 5_000L
            )
            val endpoint = AceLiveTcpPeerEndpoint("peer.test", 9_302)
            val policy = AceLiveTcpConnectionPolicy()

            connector.connect(endpoint, policy).close()
            assertEquals(listOf(preferred, alternate), started.toList())

            started.clear()
            connector.connect(endpoint, policy).close()
            assertEquals(listOf(alternate), started.toList())
        }
    }

    private fun ipv4(last: Int): InetAddress =
        InetAddress.getByAddress(byteArrayOf(127, 0, 0, last.toByte()))

    private fun ipv6(last: Int): InetAddress =
        InetAddress.getByAddress(
            ByteArray(16).also { bytes ->
                bytes[0] = 0x20
                bytes[1] = 0x01
                bytes[15] = last.toByte()
            }
        )
}
