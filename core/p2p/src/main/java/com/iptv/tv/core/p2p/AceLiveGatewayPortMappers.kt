package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal data class AceLiveGatewayUdpPolicy(
    val requestTimeoutMillis: Int = 450,
    val maxAttempts: Int = 2,
    val maxResponseBytes: Int = 1_100
) {
    init {
        require(requestTimeoutMillis in 100..2_000)
        require(maxAttempts in 1..3)
        require(maxResponseBytes in 64..4_096)
    }
}

/** Small connected-UDP boundary shared by PCP and NAT-PMP. */
internal class AceLiveGatewayUdpClient(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveGatewayUdpPolicy = AceLiveGatewayUdpPolicy(),
    private val gatewayPort: Int = PCP_NAT_PMP_PORT
) {
    suspend fun exchange(
        gateway: AceLivePortMappingGateway,
        request: ByteArray
    ): ByteArray? = withContext(ioDispatcher) {
        require(request.isNotEmpty())
        DatagramSocket(null).use { socket ->
            socket.reuseAddress = true
            gateway.bindDatagramSocket(socket)
            socket.bind(InetSocketAddress(gateway.localAddress, 0))
            socket.connect(InetSocketAddress(gateway.gatewayAddress, gatewayPort))
            socket.soTimeout = policy.requestTimeoutMillis
            repeat(policy.maxAttempts) {
                currentCoroutineContext().ensureActive()
                try {
                    socket.send(DatagramPacket(request, request.size))
                    val buffer = ByteArray(policy.maxResponseBytes + 1)
                    val packet = DatagramPacket(buffer, buffer.size)
                    socket.receive(packet)
                    if (packet.length > policy.maxResponseBytes) return@withContext null
                    return@withContext packet.data.copyOfRange(
                        packet.offset,
                        packet.offset + packet.length
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Retry only inside this small fixed attempt cap.
                }
            }
            null
        }
    }

    private companion object {
        const val PCP_NAT_PMP_PORT = 5351
    }
}

internal data class AceLivePcpMapResponse(
    val lifetimeSeconds: Int,
    val externalPort: Int,
    val assignedExternalAddress: ByteArray
)

internal object AceLivePcpCodec {
    const val VERSION = 2
    const val MAP_OPCODE = 1
    const val TCP_PROTOCOL = 6
    const val MAP_REQUEST_BYTES = 60
    const val PREFER_FAILURE_OPTION_BYTES = 4
    const val MAP_RESPONSE_MIN_BYTES = 60
    const val PREFER_FAILURE_OPTION_CODE = 2

    fun encodeMapRequest(
        localAddress: Inet4Address,
        internalPort: Int,
        externalPort: Int,
        lifetimeSeconds: Int,
        nonce: ByteArray,
        suggestedExternalAddress: ByteArray = ByteArray(IPV6_BYTES),
        preferFailure: Boolean = lifetimeSeconds > 0
    ): ByteArray {
        require(internalPort in 1..65535)
        require(externalPort in 1..65535)
        require(lifetimeSeconds >= 0)
        require(nonce.size == NONCE_BYTES)
        require(suggestedExternalAddress.size == IPV6_BYTES)
        require(lifetimeSeconds > 0 || !preferFailure) {
            "PCP PREFER_FAILURE is invalid for delete requests"
        }
        val size = MAP_REQUEST_BYTES + if (preferFailure) PREFER_FAILURE_OPTION_BYTES else 0
        return ByteBuffer.allocate(size)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(VERSION.toByte())
                put(MAP_OPCODE.toByte())
                putShort(0.toShort())
                putInt(lifetimeSeconds)
                put(ipv4MappedIpv6(localAddress))
                put(nonce)
                put(TCP_PROTOCOL.toByte())
                put(byteArrayOf(0, 0, 0))
                putShort(internalPort.toShort())
                putShort(externalPort.toShort())
                put(suggestedExternalAddress)
                if (preferFailure) {
                    put(PREFER_FAILURE_OPTION_CODE.toByte())
                    put(0.toByte())
                    putShort(0.toShort())
                }
            }
            .array()
    }

    fun decodeMapResponse(
        bytes: ByteArray,
        nonce: ByteArray,
        internalPort: Int,
        expectedExternalPort: Int
    ): AceLivePcpMapResponse? {
        if (bytes.size < MAP_RESPONSE_MIN_BYTES || nonce.size != NONCE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get().toInt() and 0xff != VERSION) return null
        if (buffer.get().toInt() and 0xff != (RESPONSE_BIT or MAP_OPCODE)) return null
        buffer.get() // reserved
        val resultCode = buffer.get().toInt() and 0xff
        val lifetime = buffer.int.toLong() and UINT_MASK
        buffer.int // epoch
        buffer.position(buffer.position() + 12) // response reserved bytes
        val responseNonce = ByteArray(NONCE_BYTES).also(buffer::get)
        if (!responseNonce.contentEquals(nonce)) return null
        if (buffer.get().toInt() and 0xff != TCP_PROTOCOL) return null
        buffer.position(buffer.position() + 3)
        val responseInternalPort = buffer.short.toInt() and 0xffff
        val responseExternalPort = buffer.short.toInt() and 0xffff
        val assignedAddress = ByteArray(IPV6_BYTES).also(buffer::get)
        if (resultCode != 0) return null
        if (responseInternalPort != internalPort) return null
        if (responseExternalPort != expectedExternalPort) return null
        if (lifetime <= 0L) return null
        return AceLivePcpMapResponse(
            lifetimeSeconds = lifetime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            externalPort = responseExternalPort,
            assignedExternalAddress = assignedAddress
        )
    }

    fun ipv4MappedIpv6(address: Inet4Address): ByteArray = ByteArray(IPV6_BYTES).apply {
        this[10] = 0xff.toByte()
        this[11] = 0xff.toByte()
        address.address.copyInto(this, destinationOffset = 12)
    }

    private const val NONCE_BYTES = 12
    private const val IPV6_BYTES = 16
    private const val RESPONSE_BIT = 0x80
    private const val UINT_MASK = 0xffff_ffffL
}

/** RFC 6887 PCP MAP with PREFER_FAILURE so the already-advertised listener port cannot drift. */
internal class AceLivePcpPortMapper(
    private val udpClient: AceLiveGatewayUdpClient = AceLiveGatewayUdpClient(),
    private val secureRandom: SecureRandom = SecureRandom()
) : AceLivePortMapper {
    override val protocol = AceLivePortMappingProtocol.PCP

    override suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort? {
        val nonce = ByteArray(12).also(secureRandom::nextBytes)
        val response = exchangeMap(
            request = request,
            nonce = nonce,
            lifetimeSeconds = request.lifetimeSeconds,
            suggestedExternalAddress = ByteArray(16),
            preferFailure = true
        ) ?: return null
        return PcpLease(
            request = request,
            nonce = nonce,
            grantedLifetimeSeconds = response.lifetimeSeconds,
            assignedExternalAddress = response.assignedExternalAddress,
            udpClient = udpClient
        )
    }

    private suspend fun exchangeMap(
        request: AceLivePortMappingRequest,
        nonce: ByteArray,
        lifetimeSeconds: Int,
        suggestedExternalAddress: ByteArray,
        preferFailure: Boolean
    ): AceLivePcpMapResponse? {
        val bytes = AceLivePcpCodec.encodeMapRequest(
            localAddress = request.gateway.localAddress,
            internalPort = request.internalPort,
            externalPort = request.requestedExternalPort,
            lifetimeSeconds = lifetimeSeconds,
            nonce = nonce,
            suggestedExternalAddress = suggestedExternalAddress,
            preferFailure = preferFailure
        )
        val response = udpClient.exchange(
            gateway = request.gateway,
            request = bytes
        ) ?: return null
        return AceLivePcpCodec.decodeMapResponse(
            bytes = response,
            nonce = nonce,
            internalPort = request.internalPort,
            expectedExternalPort = request.requestedExternalPort
        )
    }

    private class PcpLease(
        private val request: AceLivePortMappingRequest,
        private val nonce: ByteArray,
        grantedLifetimeSeconds: Int,
        private val assignedExternalAddress: ByteArray,
        private val udpClient: AceLiveGatewayUdpClient
    ) : AceLiveMappedPort {
        private val unmapped = AtomicBoolean(false)
        @Volatile
        private var grantedLifetime = grantedLifetimeSeconds

        override val protocol = AceLivePortMappingProtocol.PCP
        override val internalPort = request.internalPort
        override val externalPort = request.requestedExternalPort
        override val lifetimeSeconds: Int
            get() = grantedLifetime

        override suspend fun renew(): Boolean {
            if (unmapped.get()) return false
            val encoded = AceLivePcpCodec.encodeMapRequest(
                localAddress = request.gateway.localAddress,
                internalPort = internalPort,
                externalPort = externalPort,
                lifetimeSeconds = request.lifetimeSeconds,
                nonce = nonce,
                suggestedExternalAddress = assignedExternalAddress,
                preferFailure = true
            )
            val responseBytes = udpClient.exchange(
                gateway = request.gateway,
                request = encoded
            ) ?: return false
            val response = AceLivePcpCodec.decodeMapResponse(
                bytes = responseBytes,
                nonce = nonce,
                internalPort = internalPort,
                expectedExternalPort = externalPort
            ) ?: return false
            if (!response.assignedExternalAddress.contentEquals(assignedExternalAddress)) return false
            grantedLifetime = response.lifetimeSeconds
            return true
        }

        override suspend fun unmap() {
            if (!unmapped.compareAndSet(false, true)) return
            val encoded = AceLivePcpCodec.encodeMapRequest(
                localAddress = request.gateway.localAddress,
                internalPort = internalPort,
                externalPort = externalPort,
                lifetimeSeconds = 0,
                nonce = nonce,
                suggestedExternalAddress = assignedExternalAddress,
                preferFailure = false
            )
            udpClient.exchange(
                gateway = request.gateway,
                request = encoded
            )
        }
    }
}

internal data class AceLiveNatPmpMapResponse(
    val lifetimeSeconds: Int,
    val internalPort: Int,
    val externalPort: Int
)

internal object AceLiveNatPmpCodec {
    const val VERSION = 0
    const val MAP_TCP_OPCODE = 2
    const val MAP_REQUEST_BYTES = 12
    const val MAP_RESPONSE_BYTES = 16

    fun encodeMapRequest(
        internalPort: Int,
        suggestedExternalPort: Int,
        lifetimeSeconds: Int
    ): ByteArray {
        require(internalPort in 1..65535)
        require(suggestedExternalPort in 0..65535)
        require(lifetimeSeconds >= 0)
        return ByteBuffer.allocate(MAP_REQUEST_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(VERSION.toByte())
                put(MAP_TCP_OPCODE.toByte())
                putShort(0.toShort())
                putShort(internalPort.toShort())
                putShort(suggestedExternalPort.toShort())
                putInt(lifetimeSeconds)
            }
            .array()
    }

    fun decodeMapResponse(bytes: ByteArray, expectedInternalPort: Int): AceLiveNatPmpMapResponse? {
        if (bytes.size != MAP_RESPONSE_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (buffer.get().toInt() and 0xff != VERSION) return null
        if (buffer.get().toInt() and 0xff != RESPONSE_BIT + MAP_TCP_OPCODE) return null
        val resultCode = buffer.short.toInt() and 0xffff
        buffer.int // seconds since gateway mapping epoch
        val internalPort = buffer.short.toInt() and 0xffff
        val externalPort = buffer.short.toInt() and 0xffff
        val lifetime = buffer.int.toLong() and UINT_MASK
        if (resultCode != 0 || internalPort != expectedInternalPort) return null
        return AceLiveNatPmpMapResponse(
            lifetimeSeconds = lifetime.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            internalPort = internalPort,
            externalPort = externalPort
        )
    }

    private const val RESPONSE_BIT = 128
    private const val UINT_MASK = 0xffff_ffffL
}

/**
 * RFC 6886 NAT-PMP fallback. The first request uses a short probe lease because NAT-PMP may
 * ignore the suggested external port. An alternate assignment is never advertised and is left to
 * expire naturally instead of risking deletion of a pre-existing mapping.
 */
internal class AceLiveNatPmpPortMapper(
    private val udpClient: AceLiveGatewayUdpClient = AceLiveGatewayUdpClient()
) : AceLivePortMapper {
    override val protocol = AceLivePortMappingProtocol.NAT_PMP

    override suspend fun map(request: AceLivePortMappingRequest): AceLiveMappedPort? {
        val response = requestMapping(
            request = request,
            suggestedExternalPort = request.requestedExternalPort,
            lifetimeSeconds = minOf(request.lifetimeSeconds, PROBE_LIFETIME_SECONDS)
        ) ?: return null
        if (response.lifetimeSeconds <= 0) return null
        val lease = NatPmpLease(
            request = request,
            grantedLifetimeSeconds = response.lifetimeSeconds,
            mappedExternalPort = response.externalPort,
            udpClient = udpClient
        )
        if (response.externalPort != request.requestedExternalPort) return null
        return lease
    }

    private suspend fun requestMapping(
        request: AceLivePortMappingRequest,
        suggestedExternalPort: Int,
        lifetimeSeconds: Int
    ): AceLiveNatPmpMapResponse? {
        val encoded = AceLiveNatPmpCodec.encodeMapRequest(
            internalPort = request.internalPort,
            suggestedExternalPort = suggestedExternalPort,
            lifetimeSeconds = lifetimeSeconds
        )
        val response = udpClient.exchange(
            gateway = request.gateway,
            request = encoded
        ) ?: return null
        return AceLiveNatPmpCodec.decodeMapResponse(response, request.internalPort)
    }

    private class NatPmpLease(
        private val request: AceLivePortMappingRequest,
        grantedLifetimeSeconds: Int,
        private val mappedExternalPort: Int,
        private val udpClient: AceLiveGatewayUdpClient
    ) : AceLiveMappedPort {
        private val unmapped = AtomicBoolean(false)
        @Volatile
        private var grantedLifetime = grantedLifetimeSeconds

        override val protocol = AceLivePortMappingProtocol.NAT_PMP
        override val internalPort = request.internalPort
        override val externalPort = mappedExternalPort
        override val lifetimeSeconds: Int
            get() = grantedLifetime

        override suspend fun renew(): Boolean {
            if (unmapped.get()) return false
            val encoded = AceLiveNatPmpCodec.encodeMapRequest(
                internalPort = internalPort,
                suggestedExternalPort = externalPort,
                lifetimeSeconds = request.lifetimeSeconds
            )
            val responseBytes = udpClient.exchange(
                gateway = request.gateway,
                request = encoded
            ) ?: return false
            val response = AceLiveNatPmpCodec.decodeMapResponse(responseBytes, internalPort)
                ?: return false
            if (response.externalPort != externalPort || response.lifetimeSeconds <= 0) return false
            grantedLifetime = response.lifetimeSeconds
            return true
        }

        override suspend fun unmap() {
            if (!unmapped.compareAndSet(false, true)) return
            val encoded = AceLiveNatPmpCodec.encodeMapRequest(
                internalPort = internalPort,
                suggestedExternalPort = 0,
                lifetimeSeconds = 0
            )
            udpClient.exchange(
                gateway = request.gateway,
                request = encoded
            )
        }
    }

    private companion object {
        const val PROBE_LIFETIME_SECONDS = 120
    }
}
