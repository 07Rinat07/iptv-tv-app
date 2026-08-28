package com.iptv.tv.core.p2p

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
    val maxPreHandshakeReconnectAttempts: Int = 0,
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
        require(maxPreHandshakeReconnectAttempts in 0..MAX_RECONNECT_ATTEMPTS) {
            "maxPreHandshakeReconnectAttempts must be in 0..$MAX_RECONNECT_ATTEMPTS"
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
 * Keeps the resolver's preferred address family first, then alternates families so a bounded
 * fallback can escape a black-holed IPv6 or IPv4 path without serializing full connect timeouts.
 */
internal fun aceLiveHappyEyeballsOrder(addresses: List<InetAddress>): List<InetAddress> {
    val distinct = addresses.distinctBy { address -> address.hostAddress }
    if (distinct.size <= 1) return distinct

    val preferredIpv6 = distinct.first() is Inet6Address
    val preferred = distinct.filter { address -> (address is Inet6Address) == preferredIpv6 }
    val alternate = distinct.filter { address -> (address is Inet6Address) != preferredIpv6 }
    if (alternate.isEmpty()) return preferred

    val ordered = ArrayList<InetAddress>(distinct.size)
    for (index in 0 until maxOf(preferred.size, alternate.size)) {
        preferred.getOrNull(index)?.let(ordered::add)
        alternate.getOrNull(index)?.let(ordered::add)
    }
    return ordered
}

/**
 * Bounded Happy-Eyeballs socket acquisition for one discovered peer host.
 *
 * At most two resolved addresses are attempted. The resolver-preferred address starts first; the
 * alternate starts after a small fallback delay, or immediately if the first address fails fast.
 * First success owns the peer and cancels the losing attempt.
 */
internal class AceLiveHappyEyeballsSocketConnector(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val addressResolver: (String) -> List<InetAddress> = { host ->
        InetAddress.getAllByName(host).toList()
    },
    private val socketConnector: suspend (
        address: InetSocketAddress,
        policy: AceLiveTcpConnectionPolicy
    ) -> Socket = ::connectAceLiveSocket,
    private val fallbackDelayMillis: Long = DEFAULT_FALLBACK_DELAY_MILLIS
) {
    init {
        require(fallbackDelayMillis >= 0L) {
            "fallbackDelayMillis must be non-negative"
        }
    }

    suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): Socket = withContext(ioDispatcher) {
        val addresses = aceLiveHappyEyeballsOrder(addressResolver(endpoint.host))
            .take(MAX_RACING_ADDRESSES)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No addresses resolved for ${endpoint.host}")
        }
        raceAddresses(addresses, endpoint.port, policy)
    }

    private suspend fun raceAddresses(
        addresses: List<InetAddress>,
        port: Int,
        policy: AceLiveTcpConnectionPolicy
    ): Socket = coroutineScope {
        if (addresses.size == 1) {
            return@coroutineScope socketConnector(
                InetSocketAddress(addresses.first(), port),
                policy
            )
        }

        val winnerReady = CompletableDeferred<Unit>()
        val firstFailure = CompletableDeferred<Unit>()
        val winnerSocket = AtomicReference<Socket?>(null)
        val failureCount = AtomicInteger(0)
        val lastFailure = AtomicReference<Throwable?>(null)

        val jobs = addresses.mapIndexed { index, address ->
            launch {
                if (index > 0 && fallbackDelayMillis > 0L) {
                    withTimeoutOrNull(fallbackDelayMillis) {
                        firstFailure.await()
                    }
                }

                try {
                    val socket = socketConnector(InetSocketAddress(address, port), policy)
                    if (winnerSocket.compareAndSet(null, socket)) {
                        winnerReady.complete(Unit)
                    } else {
                        runCatching { socket.close() }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    lastFailure.set(error)
                    if (index == 0) firstFailure.complete(Unit)
                    if (failureCount.incrementAndGet() == addresses.size) {
                        winnerReady.completeExceptionally(error)
                    }
                }
            }
        }

        var returningWinner = false
        try {
            winnerReady.await()
            val winner = winnerSocket.get()
                ?: throw lastFailure.get()
                ?: IllegalStateException("Happy-Eyeballs completed without a socket")
            returningWinner = true
            winner
        } finally {
            withContext(NonCancellable) {
                jobs.forEach { job -> job.cancel() }
                jobs.forEach { job -> job.join() }
                if (!returningWinner) {
                    winnerSocket.getAndSet(null)?.let { socket ->
                        runCatching { socket.close() }
                    }
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_FALLBACK_DELAY_MILLIS = 250L
        const val MAX_RACING_ADDRESSES = 2
    }
}

/**
 * Android/JVM raw-socket implementation. Tracker/DHT discovery and peer selection stay outside.
 *
 * Hostname endpoints use a bounded Happy-Eyeballs race across resolved addresses. IP-literal peers
 * still resolve to a single address and keep the previous one-socket connect path.
 *
 * Cancellation closes the owned socket immediately. This is important for first-success startup:
 * a losing metadata peer must not keep a channel switch blocked until its read timeout expires.
 * Write deadlines are still enforced by [AceLiveTcpConnectionPool].
 */
class JvmAceLiveTcpTransportFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : AceLiveTcpTransportFactory {
    private val socketConnector = AceLiveHappyEyeballsSocketConnector(ioDispatcher = ioDispatcher)

    override suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport {
        val socket = socketConnector.connect(endpoint, policy)
        return SocketAceLiveTcpTransport(socket, ioDispatcher)
    }
}

private suspend fun connectAceLiveSocket(
    address: InetSocketAddress,
    policy: AceLiveTcpConnectionPolicy
): Socket {
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
                socket.connect(address, policy.connectTimeoutMillis)
                if (continuation.isActive) {
                    continuation.resume(Unit)
                } else {
                    runCatching { socket.close() }
                }
            } catch (error: Throwable) {
                if (continuation.isActive) {
                    continuation.resumeWithException(error)
                } else {
                    runCatching { socket.close() }
                }
            }
        }
        return socket
    } catch (error: Throwable) {
        runCatching { socket.close() }
        throw error
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
