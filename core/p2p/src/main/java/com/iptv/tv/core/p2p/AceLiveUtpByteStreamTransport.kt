package com.iptv.tv.core.p2p

import java.io.IOException
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal data class AceLiveUtpByteStreamPolicy(
    val maxBufferedInboundBytes: Int = 64 * 1024
) {
    init {
        require(maxBufferedInboundBytes in 1..MAX_BUFFERED_INBOUND_BYTES) {
            "maxBufferedInboundBytes must be in 1..$MAX_BUFFERED_INBOUND_BYTES"
        }
    }

    private companion object {
        const val MAX_BUFFERED_INBOUND_BYTES = 4 * 1024 * 1024
    }
}

/** Small seam so byte-stream behavior can be tested without owning a real UDP socket. */
internal interface AceLiveUtpStreamConnection {
    suspend fun send(bytes: ByteArray): AceLiveUtpSendResult
    suspend fun receiveOnce(): AceLiveUtpReceiveResult?
    suspend fun pollTimeout(): AceLiveUtpTimeoutResult
    fun isClosed(): Boolean
    fun close()
}

private class AceLiveUtpConnectedSocketStreamConnection(
    private val socket: AceLiveUtpConnectedSocket
) : AceLiveUtpStreamConnection {
    override suspend fun send(bytes: ByteArray): AceLiveUtpSendResult = socket.send(bytes)

    override suspend fun receiveOnce(): AceLiveUtpReceiveResult? = socket.receiveOnce()

    override suspend fun pollTimeout(): AceLiveUtpTimeoutResult = socket.pollTimeout()

    override fun isClosed(): Boolean = socket.isClosed()

    override fun close() = socket.close()
}

/**
 * Adapts one established uTP connection to the raw byte-stream contract already consumed by the
 * Ace peer handshake and connection pool.
 *
 * The adapter deliberately owns no discovery or transport-selection policy. It only preserves TCP-
 * like stream semantics over the bounded uTP session: application writes are advanced strictly by
 * [AceLiveUtpSendResult.acceptedBytes], contiguous inbound payload is queued until read, and remote
 * close is surfaced only after already-delivered bytes have been drained.
 */
internal class AceLiveUtpByteStreamTransport(
    private val connection: AceLiveUtpStreamConnection,
    private val policy: AceLiveUtpByteStreamPolicy = AceLiveUtpByteStreamPolicy()
) : AceLiveTcpTransport {
    constructor(
        socket: AceLiveUtpConnectedSocket,
        policy: AceLiveUtpByteStreamPolicy = AceLiveUtpByteStreamPolicy()
    ) : this(AceLiveUtpConnectedSocketStreamConnection(socket), policy)

    private val readMutex = Mutex()
    private val writeMutex = Mutex()
    private val receiveMutex = Mutex()
    private val stateMutex = Mutex()
    private val locallyClosed = AtomicBoolean(false)
    private val terminalFailure = AtomicReference<IOException?>(null)
    private val inbound = ArrayDeque<ByteArray>()
    private var inboundHeadOffset = 0
    private var bufferedInboundBytes = 0
    private var remoteClosed = false

    override suspend fun read(buffer: ByteArray): Int {
        require(buffer.isNotEmpty()) { "read buffer must not be empty" }
        return readMutex.withLock {
            throwIfTerminal()

            drainBuffered(buffer).takeIf { it > 0 }?.let { return@withLock it }
            if (isRemoteClosed()) return@withLock -1
            if (locallyClosed.get() || connection.isClosed()) return@withLock -1

            pumpReceiveOnce()
            throwIfTerminal()

            drainBuffered(buffer).takeIf { it > 0 }?.let { return@withLock it }
            if (isRemoteClosed()) -1 else 0
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        writeMutex.withLock {
            var offset = 0
            while (offset < bytes.size) {
                throwIfUnavailableForWrite()
                val remaining = bytes.copyOfRange(offset, bytes.size)
                val result = callConnection("uTP send failed") {
                    connection.send(remaining)
                }
                require(result.acceptedBytes in 0..remaining.size) {
                    "uTP session accepted invalid byte count ${result.acceptedBytes}"
                }
                offset += result.acceptedBytes
                if (offset >= bytes.size) return@withLock

                pumpReceiveOnce()
                if (isRemoteClosed()) {
                    throw failTerminal(IOException("Remote uTP peer closed before write completed"))
                }
            }
        }
    }

    override suspend fun close() {
        if (!locallyClosed.compareAndSet(false, true)) return
        runCatching { connection.close() }
    }

    internal suspend fun bufferedInboundByteCount(): Int = stateMutex.withLock {
        bufferedInboundBytes
    }

    private suspend fun pumpReceiveOnce() = receiveMutex.withLock {
        throwIfTerminal()
        if (locallyClosed.get() || connection.isClosed()) return@withLock

        val received = callConnection("uTP receive failed") {
            connection.receiveOnce()
        }
        if (received == null) {
            pollRetransmissionProgress()
            return@withLock
        }

        if (!received.ignored && received.deliveredBytes.isNotEmpty()) {
            enqueueInbound(received.deliveredBytes)
        }
        if (!received.ignored && received.remoteClosed) {
            stateMutex.withLock {
                remoteClosed = true
            }
            return@withLock
        }
        pollRetransmissionProgress()
    }

    private suspend fun pollRetransmissionProgress() {
        when (
            val timeout = callConnection("uTP retransmission poll failed") {
                connection.pollTimeout()
            }
        ) {
            AceLiveUtpTimeoutResult.None,
            is AceLiveUtpTimeoutResult.Retransmit -> Unit

            is AceLiveUtpTimeoutResult.Exhausted -> {
                throw failTerminal(
                    IOException(
                        "uTP retransmission budget exhausted for sequence ${timeout.sequenceNumber}"
                    )
                )
            }
        }
    }

    private suspend fun enqueueInbound(bytes: ByteArray) {
        val overflow = stateMutex.withLock {
            if (bufferedInboundBytes > policy.maxBufferedInboundBytes - bytes.size) {
                true
            } else {
                inbound.addLast(bytes.copyOf())
                bufferedInboundBytes += bytes.size
                false
            }
        }
        if (overflow) {
            throw failTerminal(
                IOException(
                    "uTP unread byte queue exceeded ${policy.maxBufferedInboundBytes} bytes"
                )
            )
        }
    }

    private suspend fun drainBuffered(target: ByteArray): Int = stateMutex.withLock {
        var copied = 0
        while (copied < target.size && inbound.isNotEmpty()) {
            val head = inbound.peekFirst()
            val available = head.size - inboundHeadOffset
            val count = minOf(target.size - copied, available)
            head.copyInto(
                destination = target,
                destinationOffset = copied,
                startIndex = inboundHeadOffset,
                endIndex = inboundHeadOffset + count
            )
            copied += count
            inboundHeadOffset += count
            bufferedInboundBytes -= count
            if (inboundHeadOffset == head.size) {
                inbound.removeFirst()
                inboundHeadOffset = 0
            }
        }
        copied
    }

    private suspend fun isRemoteClosed(): Boolean = stateMutex.withLock {
        remoteClosed
    }

    private suspend fun throwIfUnavailableForWrite() {
        throwIfTerminal()
        if (locallyClosed.get() || connection.isClosed()) {
            throw IOException("uTP byte stream is closed")
        }
        if (isRemoteClosed()) {
            throw failTerminal(IOException("Remote uTP peer is closed"))
        }
    }

    private fun throwIfTerminal() {
        terminalFailure.get()?.let { throw it }
    }

    private fun failTerminal(error: IOException): IOException {
        val existing = terminalFailure.get()
        val failure = when {
            existing != null -> existing
            terminalFailure.compareAndSet(null, error) -> error
            else -> terminalFailure.get() ?: error
        }
        locallyClosed.set(true)
        runCatching { connection.close() }
        return failure
    }

    private suspend fun <T> callConnection(
        message: String,
        block: suspend () -> T
    ): T = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: IOException) {
        throw failTerminal(error)
    } catch (error: Throwable) {
        throw failTerminal(IOException(message, error))
    }
}
