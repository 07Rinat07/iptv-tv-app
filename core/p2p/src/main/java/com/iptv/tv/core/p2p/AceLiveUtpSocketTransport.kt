package com.iptv.tv.core.p2p

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal data class AceLiveUtpSocketPolicy(
    val initialSynTimeoutMillis: Int = 1_000,
    val maxSynTimeoutMillis: Int = 2_000,
    val maxSynAttempts: Int = 3,
    val establishedReceiveTimeoutMillis: Int = 250
) {
    init {
        require(initialSynTimeoutMillis in 100..5_000)
        require(maxSynTimeoutMillis in initialSynTimeoutMillis..10_000)
        require(maxSynAttempts in 1..5)
        require(establishedReceiveTimeoutMillis in 50..5_000)
    }

    fun synTimeoutMillis(attemptIndex: Int): Int {
        require(attemptIndex in 0 until maxSynAttempts)
        var timeout = initialSynTimeoutMillis.toLong()
        repeat(attemptIndex) {
            timeout = (timeout * 2L).coerceAtMost(maxSynTimeoutMillis.toLong())
        }
        return timeout.coerceAtMost(maxSynTimeoutMillis.toLong()).toInt()
    }
}

internal data class AceLiveUtpSocketEndpoint(
    val address: InetAddress,
    val port: Int
) {
    init {
        require(port in 1..65535)
        require(!address.isAnyLocalAddress) { "uTP endpoint must not be an unspecified address" }
        require(!address.isMulticastAddress) { "uTP endpoint must not be multicast" }
    }
}

internal data class AceLiveUtpSocketBinding(
    val localAddress: InetAddress? = null,
    val bindDatagramSocket: (DatagramSocket) -> Unit = {}
)

/**
 * Owns one connected UDP socket after a successful BEP-29 client handshake.
 *
 * It is deliberately not an [AceLiveTcpTransport] yet. The future byte-stream adapter can drive
 * [send], [receiveOnce] and [pollTimeout] while this object keeps socket lifetime and session state
 * together. LEDBAT/congestion-window policy remains outside this layer.
 */
internal class AceLiveUtpConnectedSocket(
    private val socket: DatagramSocket,
    private val session: AceLiveUtpDatagramSession,
    private val ioDispatcher: CoroutineDispatcher,
    private val nanoTime: () -> Long
) : Closeable {
    private val closed = AtomicBoolean(false)
    private val sessionMutex = Mutex()

    suspend fun send(
        bytes: ByteArray,
        nowMillis: Long = monotonicMillis(),
        timestampMicros: Long = timestampMicros()
    ): AceLiveUtpSendResult {
        checkOpen()
        return sessionMutex.withLock {
            val result = session.send(bytes, nowMillis, timestampMicros)
            result.transmissions.forEach { transmission ->
                sendDatagram(transmission.datagram)
            }
            result
        }
    }

    suspend fun receiveOnce(
        nowMillis: Long = monotonicMillis(),
        nowMicros: Long = timestampMicros()
    ): AceLiveUtpReceiveResult? {
        checkOpen()
        val datagram = receiveDatagram() ?: return null
        return sessionMutex.withLock {
            val result = session.receiveDatagram(datagram, nowMillis, nowMicros)
            result.acknowledgement?.let { acknowledgement ->
                sendDatagram(acknowledgement.datagram)
            }
            result
        }
    }

    suspend fun pollTimeout(
        nowMillis: Long = monotonicMillis(),
        timestampMicros: Long = timestampMicros()
    ): AceLiveUtpTimeoutResult {
        checkOpen()
        return sessionMutex.withLock {
            val result = session.pollTimeout(nowMillis, timestampMicros)
            if (result is AceLiveUtpTimeoutResult.Retransmit) {
                sendDatagram(result.transmission.datagram)
            }
            result
        }
    }

    suspend fun inFlightPacketCount(): Int = sessionMutex.withLock {
        session.inFlightPacketCount()
    }

    fun isClosed(): Boolean = closed.get() || socket.isClosed

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        socket.close()
    }

    private suspend fun sendDatagram(bytes: ByteArray) = withContext(ioDispatcher) {
        checkOpen()
        socket.send(DatagramPacket(bytes, bytes.size))
    }

    private suspend fun receiveDatagram(): ByteArray? = withContext(ioDispatcher) {
        checkOpen()
        val buffer = ByteArray(AceLiveUtpCodec.MAX_DATAGRAM_BYTES + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
            if (packet.length > AceLiveUtpCodec.MAX_DATAGRAM_BYTES) {
                null
            } else {
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            }
        } catch (_: SocketTimeoutException) {
            null
        } catch (error: SocketException) {
            if (closed.get() || socket.isClosed) null else throw error
        }
    }

    private fun checkOpen() {
        check(!isClosed()) { "uTP socket is closed" }
    }

    private fun monotonicMillis(): Long = nanoTime() / NANOS_PER_MILLI

    private fun timestampMicros(): Long = (nanoTime() / NANOS_PER_MICRO) and UINT32_MASK

    private companion object {
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val UINT32_MASK = 0xffff_ffffL
    }
}

/** Bounded connected-UDP BEP-29 initiator. No discovery or TCP fallback policy lives here. */
internal class AceLiveUtpSocketConnector(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val secureRandom: SecureRandom = SecureRandom(),
    private val socketFactory: () -> DatagramSocket = { DatagramSocket(null) },
    private val nanoTime: () -> Long = System::nanoTime,
    private val policy: AceLiveUtpSocketPolicy = AceLiveUtpSocketPolicy()
) {
    suspend fun connect(
        endpoint: AceLiveUtpSocketEndpoint,
        binding: AceLiveUtpSocketBinding = AceLiveUtpSocketBinding(),
        sessionPolicy: AceLiveUtpSessionPolicy = AceLiveUtpSessionPolicy()
    ): AceLiveUtpConnectedSocket? = withContext(ioDispatcher) {
        val socket = socketFactory()
        var returningConnection = false
        try {
            configureSocket(socket, endpoint, binding)
            val synConnectionId = secureRandom.nextInt(UINT16_MODULUS)
            val handshake = AceLiveUtpClientHandshake(synConnectionId)
            val syn = handshake.createSyn(timestampMicros())
            val synDatagram = AceLiveUtpCodec.encode(syn)

            val state = performHandshake(socket, handshake, synDatagram)
                ?: return@withContext null
            socket.soTimeout = policy.establishedReceiveTimeoutMillis

            val session = AceLiveUtpDatagramSession(
                connectionIds = handshake.connectionIds,
                initialLocalSequenceNumber = FIRST_ESTABLISHED_LOCAL_SEQUENCE,
                initialRemoteSequenceNumber = state.header.sequenceNumber,
                initialRemoteReceiveWindowBytes = state.header.receiveWindowBytes,
                policy = sessionPolicy
            )
            val connection = AceLiveUtpConnectedSocket(
                socket = socket,
                session = session,
                ioDispatcher = ioDispatcher,
                nanoTime = nanoTime
            )
            returningConnection = true
            connection
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        } finally {
            if (!returningConnection) {
                runCatching { socket.close() }
            }
        }
    }

    private fun configureSocket(
        socket: DatagramSocket,
        endpoint: AceLiveUtpSocketEndpoint,
        binding: AceLiveUtpSocketBinding
    ) {
        socket.reuseAddress = true
        binding.bindDatagramSocket(socket)
        socket.bind(InetSocketAddress(binding.localAddress, 0))
        socket.connect(InetSocketAddress(endpoint.address, endpoint.port))
    }

    private suspend fun performHandshake(
        socket: DatagramSocket,
        handshake: AceLiveUtpClientHandshake,
        synDatagram: ByteArray
    ): AceLiveUtpPacket? {
        repeat(policy.maxSynAttempts) { attemptIndex ->
            currentCoroutineContext().ensureActive()
            socket.send(DatagramPacket(synDatagram, synDatagram.size))
            val deadlineNanos = saturatedAddNanos(
                nanoTime(),
                policy.synTimeoutMillis(attemptIndex).toLong() * NANOS_PER_MILLI
            )

            while (true) {
                currentCoroutineContext().ensureActive()
                val remainingNanos = deadlineNanos - nanoTime()
                if (remainingNanos <= 0L) break
                socket.soTimeout = nanosToTimeoutMillis(remainingNanos)

                val response = receiveHandshakePacket(socket) ?: continue
                if (handshake.acceptHandshakeResponse(response)) {
                    return response
                }
                if (handshake.phase == AceLiveUtpClientHandshakePhase.RESET) {
                    return null
                }
            }
        }
        return null
    }

    private fun receiveHandshakePacket(socket: DatagramSocket): AceLiveUtpPacket? {
        val buffer = ByteArray(AceLiveUtpCodec.MAX_DATAGRAM_BYTES + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            if (packet.length > AceLiveUtpCodec.MAX_DATAGRAM_BYTES) return null
            AceLiveUtpCodec.decode(
                packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
            )
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    private fun timestampMicros(): Long = (nanoTime() / NANOS_PER_MICRO) and UINT32_MASK

    private fun nanosToTimeoutMillis(nanos: Long): Int =
        ((nanos + NANOS_PER_MILLI - 1L) / NANOS_PER_MILLI)
            .coerceIn(1L, Int.MAX_VALUE.toLong())
            .toInt()

    private fun saturatedAddNanos(base: Long, delta: Long): Long =
        if (delta > 0L && base > Long.MAX_VALUE - delta) Long.MAX_VALUE else base + delta

    private companion object {
        const val FIRST_ESTABLISHED_LOCAL_SEQUENCE = 2
        const val UINT16_MODULUS = 1 shl 16
        const val NANOS_PER_MICRO = 1_000L
        const val NANOS_PER_MILLI = 1_000_000L
        const val UINT32_MASK = 0xffff_ffffL
    }
}
