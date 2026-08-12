package com.iptv.tv.core.p2p

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/** Network endpoint discovered by a separate tracker/DHT adapter. */
data class AceLiveTcpPeerEndpoint(
    val host: String,
    val port: Int
) {
    init {
        require(host.isNotBlank()) { "host must not be blank" }
        require(port in 1..65535) { "port must be in 1..65535" }
    }
}

/**
 * Local safety/retry policy for Ace Live TCP peers.
 *
 * These values are implementation bounds only. They are intentionally not derived from the
 * descriptor bitrate or any unverified vendor timing field.
 */
data class AceLiveTcpConnectionPolicy(
    val connectTimeoutMillis: Int = 5_000,
    val readTimeoutMillis: Int = 15_000,
    val handshakeTimeoutMillis: Int = 10_000,
    val writeTimeoutMillis: Int = 5_000,
    val readBufferBytes: Int = 64 * 1024,
    val maxConcurrentPeers: Int = 16,
    val maxReconnectAttempts: Int = 2,
    val reconnectDelayMillis: Long = 500
) {
    init {
        require(connectTimeoutMillis in 1..60_000) {
            "connectTimeoutMillis must be in 1..60000"
        }
        require(readTimeoutMillis in 100..60_000) {
            "readTimeoutMillis must be in 100..60000"
        }
        require(handshakeTimeoutMillis in 100..60_000) {
            "handshakeTimeoutMillis must be in 100..60000"
        }
        require(writeTimeoutMillis in 100..60_000) {
            "writeTimeoutMillis must be in 100..60000"
        }
        require(readBufferBytes in 1_024..MAX_READ_BUFFER_BYTES) {
            "readBufferBytes must be in 1024..$MAX_READ_BUFFER_BYTES"
        }
        require(maxConcurrentPeers in 1..MAX_CONCURRENT_PEERS) {
            "maxConcurrentPeers must be in 1..$MAX_CONCURRENT_PEERS"
        }
        require(maxReconnectAttempts in 0..MAX_RECONNECT_ATTEMPTS) {
            "maxReconnectAttempts must be in 0..$MAX_RECONNECT_ATTEMPTS"
        }
        require(reconnectDelayMillis in 0..MAX_RECONNECT_DELAY_MILLIS) {
            "reconnectDelayMillis must be in 0..$MAX_RECONNECT_DELAY_MILLIS"
        }
    }

    companion object {
        const val MAX_READ_BUFFER_BYTES: Int = 1024 * 1024
        const val MAX_CONCURRENT_PEERS: Int = 64
        const val MAX_RECONNECT_ATTEMPTS: Int = 10
        const val MAX_RECONNECT_DELAY_MILLIS: Long = 30_000
    }
}

/** Minimal raw TCP boundary used by the connection pool and faked by unit tests. */
interface AceLiveTcpTransport {
    /** Returns >0 bytes, 0 for a bounded read timeout, or -1 for remote EOF. */
    suspend fun read(buffer: ByteArray): Int

    suspend fun write(bytes: ByteArray)

    suspend fun close()
}

fun interface AceLiveTcpTransportFactory {
    suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport
}

/**
 * Android/JVM raw-socket implementation. Tracker/DHT discovery and peer selection stay outside.
 *
 * Cancellation closes the owned socket immediately. This is important for first-success startup:
 * a losing metadata peer must not keep a channel switch blocked until its read timeout expires.
 * Write deadlines are still enforced by [AceLiveTcpConnectionPool].
 */
class JvmAceLiveTcpTransportFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AceLiveTcpTransportFactory {
    override suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport = withContext(ioDispatcher) {
        val socket = Socket()
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    runCatching { socket.close() }
                }
                try {
                    socket.tcpNoDelay = true
                    socket.keepAlive = true
                    socket.soTimeout = policy.readTimeoutMillis
                    socket.connect(
                        InetSocketAddress(endpoint.host, endpoint.port),
                        policy.connectTimeoutMillis
                    )
                    if (continuation.isActive) {
                        continuation.resume(Unit)
                    } else {
                        runCatching { socket.close() }
                    }
                } catch (error: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(error)
                    }
                }
            }
            SocketAceLiveTcpTransport(socket, ioDispatcher)
        } catch (error: Throwable) {
            runCatching { socket.close() }
            throw error
        }
    }
}

private class SocketAceLiveTcpTransport(
    private val socket: Socket,
    private val ioDispatcher: CoroutineDispatcher
) : AceLiveTcpTransport {
    override suspend fun read(buffer: ByteArray): Int = withContext(ioDispatcher) {
        require(buffer.isNotEmpty()) { "read buffer must not be empty" }
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation {
                runCatching { socket.close() }
            }
            try {
                val count = socket.getInputStream().read(buffer)
                if (continuation.isActive) continuation.resume(count)
            } catch (_: SocketTimeoutException) {
                if (continuation.isActive) continuation.resume(0)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withContext(ioDispatcher) {
            suspendCancellableCoroutine<Unit> { continuation ->
                continuation.invokeOnCancellation {
                    runCatching { socket.close() }
                }
                try {
                    val output = socket.getOutputStream()
                    output.write(bytes)
                    output.flush()
                    if (continuation.isActive) continuation.resume(Unit)
                } catch (error: Throwable) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    override suspend fun close() {
        withContext(NonCancellable + ioDispatcher) {
            runCatching { socket.close() }
        }
    }
}
