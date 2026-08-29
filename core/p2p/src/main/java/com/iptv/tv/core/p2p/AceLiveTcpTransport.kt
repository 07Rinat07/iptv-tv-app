package com.iptv.tv.core.p2p

import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.LinkedHashMap
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
    val maxConcurrentInboundPeers: Int = 4,
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
        require(maxConcurrentInboundPeers in 0..MAX_CONCURRENT_INBOUND_PEERS) {
            "maxConcurrentInboundPeers must be in 0..$MAX_CONCURRENT_INBOUND_PEERS"
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
        const val MAX_CONCURRENT_INBOUND_PEERS: Int = 16
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

/** Adopts a socket accepted by the runtime's advertised peer listener. */
internal fun adoptAceLiveAcceptedSocket(
    socket: Socket,
    policy: AceLiveTcpConnectionPolicy,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
): AceLiveTcpTransport {
    require(socket.isConnected && !socket.isClosed) { "accepted peer socket must be connected" }
    socket.tcpNoDelay = true
    socket.keepAlive = true
    socket.soTimeout = policy.readTimeoutMillis
    return SocketAceLiveTcpTransport(socket, ioDispatcher)
}

/**
 * Keeps the resolver's preferred address family first, unless a recent successful connection for
 * the same host gives us stronger local evidence. Families are still alternated so the fallback
 * remains bounded and can escape a path that has changed since the remembered success.
 */
internal fun aceLiveHappyEyeballsOrder(
    addresses: List<InetAddress>,
    preferredIpv6: Boolean? = null
): List<InetAddress> {
    val distinct = addresses.distinctBy { address -> address.hostAddress }
    if (distinct.size <= 1) return distinct

    val resolverPreferredIpv6 = distinct.first() is Inet6Address
    val selectedPreferredIpv6 = preferredIpv6
        ?.takeIf { family -> distinct.any { address -> (address is Inet6Address) == family } }
        ?: resolverPreferredIpv6
    val preferred = distinct.filter { address ->
        (address is Inet6Address) == selectedPreferredIpv6
    }
    val alternate = distinct.filter { address ->
        (address is Inet6Address) != selectedPreferredIpv6
    }
    if (alternate.isEmpty()) return preferred

    val ordered = ArrayList<InetAddress>(distinct.size)
    for (index in 0 until maxOf(preferred.size, alternate.size)) {
        preferred.getOrNull(index)?.let(ordered::add)
        alternate.getOrNull(index)?.let(ordered::add)
    }
    return ordered
}

/**
 * Small process-wide LRU of successful address families.
 *
 * This is only a connect-order hint. It never suppresses the alternate family and is bounded so
 * arbitrary peer hostnames cannot create unbounded process state.
 */
internal class AceLiveHappyEyeballsAddressFamilyMemory(
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val stateLock = Any()
    private val preferredIpv6ByHost = LinkedHashMap<String, Boolean>(16, 0.75f, true)

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    fun preferredIpv6(host: String): Boolean? = synchronized(stateLock) {
        preferredIpv6ByHost[normalizeHost(host)]
    }

    fun recordSuccess(host: String, address: InetAddress) = synchronized(stateLock) {
        preferredIpv6ByHost[normalizeHost(host)] = address is Inet6Address
        while (preferredIpv6ByHost.size > maxEntries) {
            val iterator = preferredIpv6ByHost.entries.iterator()
            if (!iterator.hasNext()) break
            iterator.next()
            iterator.remove()
        }
    }

    private fun normalizeHost(host: String): String =
        host.trim().trimEnd('.').lowercase()

    companion object {
        val shared = AceLiveHappyEyeballsAddressFamilyMemory()

        private const val DEFAULT_MAX_ENTRIES = 128
    }
}

/**
 * Bounded Happy-Eyeballs socket acquisition for one discovered peer host.
 *
 * At most two resolved addresses are attempted. The preferred address starts first; the alternate
 * starts after a small fallback delay, or immediately if the first address fails fast. A recent
 * successful address family for the same host may become preferred on later connects. First success
 * owns the peer and cancels the losing attempt.
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
    private val familyMemory: AceLiveHappyEyeballsAddressFamilyMemory =
        AceLiveHappyEyeballsAddressFamilyMemory.shared,
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
        val addresses = aceLiveHappyEyeballsOrder(
            addresses = addressResolver(endpoint.host),
            preferredIpv6 = familyMemory.preferredIpv6(endpoint.host)
        ).take(MAX_RACING_ADDRESSES)
        if (addresses.isEmpty()) {
            throw UnknownHostException("No addresses resolved for ${endpoint.host}")
        }
        raceAddresses(addresses, endpoint.host, endpoint.port, policy)
    }

    private suspend fun raceAddresses(
        addresses: List<InetAddress>,
        host: String,
        port: Int,
        policy: AceLiveTcpConnectionPolicy
    ): Socket = coroutineScope {
        if (addresses.size == 1) {
            val address = addresses.first()
            return@coroutineScope socketConnector(
                InetSocketAddress(address, port),
                policy
            ).also {
                familyMemory.recordSuccess(host, address)
            }
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
                        familyMemory.recordSuccess(host, address)
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
 * Android/JVM outbound transport implementation.
 *
 * TCP keeps its existing bounded Happy-Eyeballs acquisition. In parallel, a uTP candidate targets
 * the same discovered peer endpoint. Physical connection success alone does not select the winner:
 * [AceLiveTcpUtpRacingTransportFactory] mirrors the pool's first public Ace handshake to both live
 * candidates and retains only the first transport that returns a swarm-valid handshake.
 *
 * This preserves the existing connect/read/write budgets and avoids cancelling a viable fallback
 * merely because the other transport completed its lower-level handshake first.
 */
class JvmAceLiveTcpTransportFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val metricsReporter: P2pRuntimeMetricsReporter = P2pRuntimeMetricsReporter.LOGCAT
) : AceLiveTcpTransportFactory {
    private val socketConnector = AceLiveHappyEyeballsSocketConnector(ioDispatcher = ioDispatcher)
    private val utpFactory = JvmAceLiveUtpTransportFactory(ioDispatcher = ioDispatcher)
    private val racingFactory = AceLiveTcpUtpRacingTransportFactory(
        tcpConnect = { endpoint, policy ->
            val socket = socketConnector.connect(endpoint, policy)
            SocketAceLiveTcpTransport(socket, ioDispatcher)
        },
        utpConnect = utpFactory::connect,
        ioDispatcher = ioDispatcher,
        metricsReporter = metricsReporter
    )

    override suspend fun connect(
        endpoint: AceLiveTcpPeerEndpoint,
        policy: AceLiveTcpConnectionPolicy
    ): AceLiveTcpTransport = racingFactory.connect(endpoint, policy)
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
