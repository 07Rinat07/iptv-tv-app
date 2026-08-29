package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveGatewayPortMappersTest {
    private val loopback = ipv4("127.0.0.1")

    @Test
    fun `PCP keeps mapping nonce across renewal and removes mapping without prefer failure`() = runBlocking {
        UdpGatewayHarness(loopback, expectedRequests = 3) { request, index ->
            buildPcpResponse(request, lifetimeSeconds = if (index == 2) 0 else 3600)
        }.use { gateway ->
            val mapper = AceLivePcpPortMapper(udpClient(gateway.port))
            val mapped = mapper.map(request(45000))
            assertNotNull(mapped)
            mapped!!

            assertTrue(mapped.renew())
            mapped.unmap()
            assertTrue(gateway.awaitRequests())

            val requests = gateway.requests
            assertEquals(3, requests.size)
            assertEquals(64, requests[0].size)
            assertEquals(AceLivePcpCodec.PREFER_FAILURE_OPTION_CODE, requests[0][60].toInt() and 0xff)
            assertEquals(64, requests[1].size)
            assertEquals(60, requests[2].size)
            assertEquals(0, readInt(requests[2], 4))
            assertTrue(requests[0].copyOfRange(24, 36).contentEquals(requests[1].copyOfRange(24, 36)))
            assertTrue(requests[0].copyOfRange(24, 36).contentEquals(requests[2].copyOfRange(24, 36)))
        }
    }

    @Test
    fun `PCP rejects response with mismatched nonce`() = runBlocking {
        UdpGatewayHarness(loopback, expectedRequests = 1) { request, _ ->
            buildPcpResponse(request, lifetimeSeconds = 3600).also { response ->
                response[24] = (response[24].toInt() xor 0x7f).toByte()
            }
        }.use { gateway ->
            val mapper = AceLivePcpPortMapper(udpClient(gateway.port))
            assertNull(mapper.map(request(45100)))
            assertTrue(gateway.awaitRequests())
        }
    }

    @Test
    fun `NAT PMP alternate external port uses short probe and is never advertised`() = runBlocking {
        UdpGatewayHarness(loopback, expectedRequests = 1) { request, _ ->
            val internalPort = readUShort(request, 4)
            buildNatPmpResponse(internalPort, internalPort + 1, readInt(request, 8))
        }.use { gateway ->
            val mapper = AceLiveNatPmpPortMapper(udpClient(gateway.port))
            assertNull(mapper.map(request(45200)))
            assertTrue(gateway.awaitRequests())

            val requests = gateway.requests
            assertEquals(1, requests.size)
            assertEquals(45200, readUShort(requests[0], 6))
            assertEquals(120, readInt(requests[0], 8))
        }
    }

    @Test
    fun `NAT PMP exact port renews and unmaps idempotently`() = runBlocking {
        UdpGatewayHarness(loopback, expectedRequests = 3) { request, _ ->
            val internalPort = readUShort(request, 4)
            val lifetime = readInt(request, 8)
            val externalPort = if (lifetime == 0) 0 else readUShort(request, 6)
            buildNatPmpResponse(internalPort, externalPort, lifetime)
        }.use { gateway ->
            val mapper = AceLiveNatPmpPortMapper(udpClient(gateway.port))
            val mapped = mapper.map(request(45300))
            assertNotNull(mapped)
            mapped!!
            assertEquals(45300, mapped.externalPort)
            assertEquals(120, mapped.lifetimeSeconds)
            assertTrue(mapped.renew())
            mapped.unmap()
            mapped.unmap()
            assertTrue(gateway.awaitRequests())
            assertEquals(3, gateway.requests.size)
            assertEquals(120, readInt(gateway.requests[0], 8))
            assertEquals(3600, readInt(gateway.requests[1], 8))
        }
    }

    @Test
    fun `gateway UDP client binds socket through selected network before exchange`() = runBlocking {
        val bindCalls = AtomicInteger(0)
        UdpGatewayHarness(loopback, expectedRequests = 1) { request, _ ->
            buildPcpResponse(request, lifetimeSeconds = 3600)
        }.use { gateway ->
            val request = AceLivePortMappingRequest(
                gateway = AceLivePortMappingGateway(
                    localAddress = loopback,
                    gatewayAddress = loopback,
                    bindDatagramSocket = { bindCalls.incrementAndGet() }
                ),
                internalPort = 45350,
                requestedExternalPort = 45350,
                lifetimeSeconds = 3600
            )
            val mapped = AceLivePcpPortMapper(udpClient(gateway.port)).map(request)
            assertNotNull(mapped)
            assertEquals(1, bindCalls.get())
            mapped?.unmap()
        }
        Unit
    }

    @Test
    fun `PCP and NAT PMP codecs reject malformed response identity`() {
        val nonce = ByteArray(12) { it.toByte() }
        val local = ipv4("192.168.50.7")
        val pcpRequest = AceLivePcpCodec.encodeMapRequest(
            localAddress = local,
            internalPort = 45400,
            externalPort = 45400,
            lifetimeSeconds = 3600,
            nonce = nonce
        )
        val pcpResponse = buildPcpResponse(pcpRequest, 3600)
        assertNull(
            AceLivePcpCodec.decodeMapResponse(
                bytes = pcpResponse,
                nonce = ByteArray(12) { 9 },
                internalPort = 45400,
                expectedExternalPort = 45400
            )
        )

        val natResponse = buildNatPmpResponse(45400, 45400, 3600)
        assertNull(AceLiveNatPmpCodec.decodeMapResponse(natResponse, 45401))
        assertFalse(AceLiveNatPmpCodec.decodeMapResponse(natResponse.copyOf(15), 45400) != null)
    }

    private fun request(port: Int) = AceLivePortMappingRequest(
        gateway = AceLivePortMappingGateway(loopback, loopback),
        internalPort = port,
        requestedExternalPort = port,
        lifetimeSeconds = 3600
    )

    private fun udpClient(port: Int) = AceLiveGatewayUdpClient(
        policy = AceLiveGatewayUdpPolicy(
            requestTimeoutMillis = 500,
            maxAttempts = 1,
            maxResponseBytes = 1_100
        ),
        gatewayPort = port
    )

    private fun buildPcpResponse(request: ByteArray, lifetimeSeconds: Int): ByteArray {
        val nonce = request.copyOfRange(24, 36)
        val internalPort = readUShort(request, 40)
        val externalPort = readUShort(request, 42)
        return ByteBuffer.allocate(60)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(2.toByte())
                put(0x81.toByte())
                put(0.toByte())
                put(0.toByte())
                putInt(lifetimeSeconds)
                putInt(1234)
                put(ByteArray(12))
                put(nonce)
                put(6.toByte())
                put(byteArrayOf(0, 0, 0))
                putShort(internalPort.toShort())
                putShort(externalPort.toShort())
                put(AceLivePcpCodec.ipv4MappedIpv6(ipv4("203.0.113.9")))
            }
            .array()
    }

    private fun buildNatPmpResponse(
        internalPort: Int,
        externalPort: Int,
        lifetimeSeconds: Int
    ): ByteArray = ByteBuffer.allocate(16)
        .order(ByteOrder.BIG_ENDIAN)
        .apply {
            put(0.toByte())
            put(130.toByte())
            putShort(0)
            putInt(1234)
            putShort(internalPort.toShort())
            putShort(externalPort.toShort())
            putInt(lifetimeSeconds)
        }
        .array()

    private fun readUShort(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int

    private fun ipv4(value: String): Inet4Address = InetAddress.getByName(value) as Inet4Address

    private class UdpGatewayHarness(
        address: Inet4Address,
        private val expectedRequests: Int,
        private val responder: (ByteArray, Int) -> ByteArray
    ) : AutoCloseable {
        private val socket = DatagramSocket(0, address).apply { soTimeout = 2_000 }
        private val finished = CountDownLatch(1)
        val requests = CopyOnWriteArrayList<ByteArray>()
        val port: Int = socket.localPort
        private val thread = Thread({ runServer() }, "nat-test-gateway").apply {
            isDaemon = true
            start()
        }

        fun awaitRequests(): Boolean = finished.await(2, TimeUnit.SECONDS)

        private fun runServer() {
            try {
                repeat(expectedRequests) { index ->
                    val buffer = ByteArray(2_048)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    val request = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    requests += request
                    val response = responder(request, index)
                    socket.send(DatagramPacket(response, response.size, packet.socketAddress))
                }
            } catch (_: SocketTimeoutException) {
                // The assertion sees the missing request count through the latch/list.
            } finally {
                finished.countDown()
            }
        }

        override fun close() {
            socket.close()
            thread.join(1_000)
        }
    }
}
