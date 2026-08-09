package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class AceLiveUdpTrackerDiscoveryTest {
    @Test
    fun `swarm key accepts exactly 40 hex and keeps byte ownership`() {
        val key = AceLiveSwarmKey.parseHex("00112233445566778899AABBCCDDEEFF00112233")!!
        val firstCopy = key.toByteArray()
        firstCopy[0] = 0x7F

        assertEquals("00112233445566778899aabbccddeeff00112233", key.toHex())
        assertEquals(0, key.toByteArray()[0].toInt())
        assertNull(AceLiveSwarmKey.parseHex("1234"))
        assertFalse(key.toString().contains(key.toHex()))
    }

    @Test
    fun `connect codec uses BEP15 layout and validates transaction id`() {
        val request = AceLiveUdpTrackerCodec.encodeConnectRequest(0x11223344)
        assertEquals(AceLiveUdpTrackerCodec.CONNECT_REQUEST_BYTES, request.size)
        assertArrayEquals(
            byteArrayOf(0, 0, 4, 23, 39, 16, 25, -128),
            request.copyOfRange(0, 8)
        )
        assertEquals(0, intAt(request, 8))
        assertEquals(0x11223344, intAt(request, 12))

        val response = connectResponse(0x11223344)
        assertEquals(
            CONNECTION_ID,
            AceLiveUdpTrackerCodec.decodeConnectResponse(response, 0x11223344)
        )
        expectProtocolFailure {
            AceLiveUdpTrackerCodec.decodeConnectResponse(response, 0x55667788)
        }
    }

    @Test
    fun `announce codec places swarm peer id and explicit peer port at standard offsets`() {
        val swarm = AceLiveSwarmKey.parseHex(SWARM_HEX)!!
        val request = AceLiveUdpTrackerCodec.encodeAnnounceRequest(
            connectionId = CONNECTION_ID,
            transactionId = 0x11223344,
            swarmKey = swarm,
            peerId = PEER_ID,
            announcePort = 8621,
            key = 0x55667788,
            numWant = 50
        )

        assertEquals(AceLiveUdpTrackerCodec.ANNOUNCE_REQUEST_BYTES, request.size)
        assertEquals(1, intAt(request, 8))
        assertEquals(0x11223344, intAt(request, 12))
        assertArrayEquals(swarm.toByteArray(), request.copyOfRange(16, 36))
        assertArrayEquals(PEER_ID, request.copyOfRange(36, 56))
        assertEquals(2, intAt(request, 80))
        assertEquals(0x55667788, intAt(request, 88))
        assertEquals(50, intAt(request, 92))
        assertEquals(8621, shortAt(request, 96))
    }

    @Test
    fun `announce decoder rejects malformed compact peer tail and oversized peer count`() {
        val malformed = ByteBuffer.allocate(21)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(1)
            .putInt(7)
            .putInt(1800)
            .putInt(0)
            .putInt(0)
            .put(1)
            .array()
        expectProtocolFailure {
            AceLiveUdpTrackerCodec.decodeAnnounceResponse(
                bytes = malformed,
                expectedTransactionId = 7,
                maxPeers = 4,
                maxResponseBytes = 128
            )
        }

        val twoPeers = announceResponse(
            transactionId = 7,
            peers = listOf("1.2.3.4" to 1111, "5.6.7.8" to 2222)
        )
        expectProtocolFailure {
            AceLiveUdpTrackerCodec.decodeAnnounceResponse(
                bytes = twoPeers,
                expectedTransactionId = 7,
                maxPeers = 1,
                maxResponseBytes = 128
            )
        }
    }

    @Test
    fun `tracker parser accepts only explicit udp endpoints`() {
        val discovery = AceLiveUdpTrackerDiscovery()
        assertEquals(
            AceLiveUdpTrackerEndpoint("tracker.example", 2710),
            discovery.parseTrackerEndpoint("udp://tracker.example:2710/announce")
        )
        assertNull(discovery.parseTrackerEndpoint("https://tracker.example:2710/announce"))
        assertNull(discovery.parseTrackerEndpoint("tracker.example:2710"))
        assertNull(discovery.parseTrackerEndpoint("udp://user@tracker.example:2710"))
        assertNull(discovery.parseTrackerEndpoint("udp://tracker.example"))
    }

    @Test
    fun `default policy refuses loopback and special-use descriptor trackers`() = runBlocking {
        val discovery = AceLiveUdpTrackerDiscovery()
        val result = discovery.discover(
            request(
                trackers = listOf(
                    "udp://127.0.0.1:2710/announce",
                    "udp://192.0.0.1:2710/announce",
                    "udp://192.88.99.1:2710/announce",
                    "udp://198.51.100.7:2710/announce"
                )
            )
        )

        assertTrue(result.peers.isEmpty())
        assertEquals(0, result.attemptedTrackers)
        assertEquals(0, result.failedTrackers)
        assertEquals(4, result.rejectedTrackers)
    }

    @Test
    fun `cancellation after a blocked receive stops before retry or next tracker`() = runBlocking {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { tracker ->
            tracker.soTimeout = 1_000
            val firstPacket = async(Dispatchers.IO) { receive(tracker) }
            val discovery = AceLiveUdpTrackerDiscovery(
                policy = localPolicy(
                    requestTimeoutMillis = 100,
                    maxRequestAttempts = 2,
                    retryBaseDelayMillis = 10
                )
            )
            val job = async {
                discovery.discover(
                    request(
                        trackers = listOf(
                            "udp://127.0.0.1:${tracker.localPort}/one",
                            "udp://127.0.0.1:${tracker.localPort}/two",
                            "udp://127.0.0.1:${tracker.localPort}/three"
                        )
                    )
                )
            }

            firstPacket.await()
            job.cancelAndJoin()

            tracker.soTimeout = 250
            try {
                receive(tracker)
                fail("cancelled discovery sent a retry or advanced to another tracker")
            } catch (_: SocketTimeoutException) {
                Unit
            }
        }
    }

    @Test
    fun `lost connect and announce datagrams are retransmitted with bounded attempts`() = runBlocking {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { tracker ->
            tracker.soTimeout = 5_000
            val server = async(Dispatchers.IO) {
                val firstConnect = receive(tracker)
                val secondConnect = receive(tracker)
                assertEquals(intAt(firstConnect.data, firstConnect.offset + 12), intAt(secondConnect.data, secondConnect.offset + 12))
                send(tracker, connectResponse(intAt(secondConnect.data, secondConnect.offset + 12)), secondConnect)

                val firstAnnounce = receive(tracker)
                val secondAnnounce = receive(tracker)
                assertEquals(intAt(firstAnnounce.data, firstAnnounce.offset + 12), intAt(secondAnnounce.data, secondAnnounce.offset + 12))
                send(
                    tracker,
                    announceResponse(
                        transactionId = intAt(secondAnnounce.data, secondAnnounce.offset + 12),
                        peers = listOf("9.9.9.9" to 1234)
                    ),
                    secondAnnounce
                )
            }

            val randomValues = intArrayOf(0x11223344, 0x22334455, 0x33445566)
            var randomIndex = 0
            val discovery = AceLiveUdpTrackerDiscovery(
                policy = localPolicy(
                    requestTimeoutMillis = 100,
                    maxRequestAttempts = 2,
                    retryBaseDelayMillis = 10
                ),
                randomInt = { randomValues[randomIndex++] }
            )
            val result = discovery.discover(request(tracker.localPort))
            server.await()

            assertEquals(listOf(AceLiveTcpPeerEndpoint("9.9.9.9", 1234)), result.peers)
            assertEquals(1, result.attemptedTrackers)
            assertEquals(0, result.failedTrackers)
        }
    }

    @Test
    fun `tracker hostname falls through to later allowed address`() = runBlocking {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { tracker ->
            tracker.soTimeout = 5_000
            val server = async(Dispatchers.IO) {
                val connectPacket = receive(tracker)
                val connectTx = intAt(connectPacket.data, connectPacket.offset + 12)
                send(tracker, connectResponse(connectTx), connectPacket)

                val announcePacket = receive(tracker)
                val announceTx = intAt(announcePacket.data, announcePacket.offset + 12)
                send(
                    tracker,
                    announceResponse(
                        transactionId = announceTx,
                        peers = listOf("8.8.8.8" to 4321)
                    ),
                    announcePacket
                )
            }

            val randomValues = intArrayOf(
                0x11223344,
                0x22334455,
                0x33445566,
                0x44556677
            )
            var randomIndex = 0
            val discovery = AceLiveUdpTrackerDiscovery(
                policy = localPolicy(
                    requestTimeoutMillis = 100,
                    maxRequestAttempts = 1,
                    maxResolvedAddressesPerTracker = 2
                ),
                randomInt = { randomValues[randomIndex++] },
                addressResolver = {
                    listOf(
                        InetAddress.getByName("127.0.0.2") as Inet4Address,
                        InetAddress.getByName("127.0.0.1") as Inet4Address
                    )
                }
            )
            val result = discovery.discover(
                request(trackers = listOf("udp://tracker.test:${tracker.localPort}/announce"))
            )
            server.await()

            assertEquals(listOf(AceLiveTcpPeerEndpoint("8.8.8.8", 4321)), result.peers)
            assertEquals(1, result.attemptedTrackers)
            assertEquals(0, result.failedTrackers)
        }
    }

    @Test
    fun `local fake tracker completes connect announce dedupes peers and drops private peers`() = runBlocking {
        DatagramSocket(InetSocketAddress("127.0.0.1", 0)).use { tracker ->
            tracker.soTimeout = 5_000
            val server = async(Dispatchers.IO) {
                val connectPacket = receive(tracker)
                assertEquals(16, connectPacket.length)
                assertEquals(0, intAt(connectPacket.data, connectPacket.offset + 8))
                assertEquals(0x11223344, intAt(connectPacket.data, connectPacket.offset + 12))
                send(tracker, connectResponse(0x11223344), connectPacket)

                val announcePacket = receive(tracker)
                val announce = announcePacket.data.copyOfRange(
                    announcePacket.offset,
                    announcePacket.offset + announcePacket.length
                )
                assertEquals(98, announce.size)
                assertEquals(1, intAt(announce, 8))
                assertEquals(0x22334455, intAt(announce, 12))
                assertArrayEquals(
                    AceLiveSwarmKey.parseHex(SWARM_HEX)!!.toByteArray(),
                    announce.copyOfRange(16, 36)
                )
                assertArrayEquals(PEER_ID, announce.copyOfRange(36, 56))
                assertEquals(2, intAt(announce, 80))
                assertEquals(0x33445566, intAt(announce, 88))
                assertEquals(8621, shortAt(announce, 96))

                send(
                    tracker,
                    announceResponse(
                        transactionId = 0x22334455,
                        peers = listOf(
                            "9.9.9.9" to 1234,
                            "9.9.9.9" to 1234,
                            "10.0.0.8" to 9999,
                            "8.8.8.8" to 4321
                        )
                    ),
                    announcePacket
                )
            }

            val randomValues = intArrayOf(0x11223344, 0x22334455, 0x33445566)
            var randomIndex = 0
            val discovery = AceLiveUdpTrackerDiscovery(
                policy = localPolicy(requestTimeoutMillis = 2_000),
                randomInt = { randomValues[randomIndex++] }
            )
            val result = discovery.discover(request(tracker.localPort))
            server.await()

            assertEquals(
                listOf(
                    AceLiveTcpPeerEndpoint("9.9.9.9", 1234),
                    AceLiveTcpPeerEndpoint("8.8.8.8", 4321)
                ),
                result.peers
            )
            assertEquals(1, result.attemptedTrackers)
            assertEquals(0, result.failedTrackers)
            assertEquals(0, result.rejectedTrackers)
        }
    }

    private fun localPolicy(
        requestTimeoutMillis: Int,
        maxRequestAttempts: Int = 2,
        retryBaseDelayMillis: Long = 0,
        maxResolvedAddressesPerTracker: Int = 4
    ): AceLiveUdpTrackerPolicy = AceLiveUdpTrackerPolicy(
        requestTimeoutMillis = requestTimeoutMillis,
        maxRequestAttempts = maxRequestAttempts,
        retryBaseDelayMillis = retryBaseDelayMillis,
        discoveryBudgetMillis = 5_000,
        maxResolvedAddressesPerTracker = maxResolvedAddressesPerTracker,
        allowNonGlobalTrackerAddresses = true
    )

    private fun request(port: Int): AceLiveUdpTrackerDiscoveryRequest =
        request(trackers = listOf("udp://127.0.0.1:$port/announce"))

    private fun request(trackers: List<String>): AceLiveUdpTrackerDiscoveryRequest =
        AceLiveUdpTrackerDiscoveryRequest(
            swarmKey = AceLiveSwarmKey.parseHex(SWARM_HEX)!!,
            trackers = trackers,
            peerId = PEER_ID,
            announcePort = 8621
        )

    private fun receive(socket: DatagramSocket): DatagramPacket {
        val buffer = ByteArray(2048)
        return DatagramPacket(buffer, buffer.size).also(socket::receive)
    }

    private fun send(socket: DatagramSocket, bytes: ByteArray, request: DatagramPacket) {
        socket.send(DatagramPacket(bytes, bytes.size, request.socketAddress))
    }

    private fun connectResponse(transactionId: Int): ByteArray =
        ByteBuffer.allocate(16)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(0)
            .putInt(transactionId)
            .putLong(CONNECTION_ID)
            .array()

    private fun announceResponse(
        transactionId: Int,
        peers: List<Pair<String, Int>>
    ): ByteArray {
        val buffer = ByteBuffer.allocate(20 + peers.size * 6).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(1)
        buffer.putInt(transactionId)
        buffer.putInt(1800)
        buffer.putInt(0)
        buffer.putInt(peers.size)
        peers.forEach { (host, port) ->
            host.split('.').forEach { buffer.put(it.toInt().toByte()) }
            buffer.putShort(port.toShort())
        }
        return buffer.array()
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun shortAt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF

    private fun expectProtocolFailure(block: () -> Unit) {
        try {
            block()
            throw AssertionError("Expected AceLiveTrackerProtocolException")
        } catch (_: AceLiveTrackerProtocolException) {
            Unit
        }
    }

    private companion object {
        const val SWARM_HEX = "00112233445566778899aabbccddeeff00112233"
        const val CONNECTION_ID = 0x0102030405060708L
        val PEER_ID = ByteArray(20) { (it + 1).toByte() }
    }
}
