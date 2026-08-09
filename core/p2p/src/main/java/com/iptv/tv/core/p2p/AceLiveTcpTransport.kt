package com.iptv.tv.core.p2p

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = policy.readTimeoutMillis
            socket.connect(
                InetSocketAddress(endpoint.host, endpoint.port),
                policy.connectTimeoutMillis
            )
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
        try {
            socket.getInputStream().read(buffer)
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    override suspend fun write(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        withContext(ioDispatcher) {
            val output = socket.getOutputStream()
            output.write(bytes)
            output.flush()
        }
    }

    override suspend fun close() {
        withContext(ioDispatcher) {
            runCatching { socket.close() }
        }
    }
}
