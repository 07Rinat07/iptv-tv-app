package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

private data class AceLiveIpv4Cidr(
    val network: Long,
    val prefixBits: Int
)

/** Local safety bounds for descriptor-provided UDP tracker discovery. */
data class AceLiveUdpTrackerPolicy(
    val requestTimeoutMillis: Int = 2_000,
    val maxRequestAttempts: Int = 2,
    val retryBaseDelayMillis: Long = 200,
    val discoveryBudgetMillis: Long = 20_000,
    val maxTrackers: Int = 32,
    val maxResolvedAddressesPerTracker: Int = 4,
    val maxTrackerUrlLength: Int = 256,
    val maxResponseBytes: Int = 8 * 1024,
    val maxPeersPerTracker: Int = 128,
    val maxTotalPeers: Int = 256,
    val numWant: Int = 50,
    val allowNonGlobalTrackerAddresses: Boolean = false,
    val allowNonGlobalPeerAddresses: Boolean = false
) {
    init {
        require(requestTimeoutMillis in 100..30_000)
        require(maxRequestAttempts in 1..5)
        require(retryBaseDelayMillis in 0..5_000)
        require(discoveryBudgetMillis in requestTimeoutMillis.toLong()..120_000L)
        require(maxTrackers in 1..128)
        require(maxResolvedAddressesPerTracker in 1..16)
        require(maxTrackerUrlLength in 16..2_048)
        require(maxResponseBytes in AceLiveUdpTrackerCodec.ANNOUNCE_RESPONSE_HEADER_BYTES..65_507)
        require(maxPeersPerTracker in 1..AceLiveUdpTrackerCodec.MAX_PARSED_PEERS)
        require(maxTotalPeers in 1..2_048)
        require(numWant in 1..AceLiveUdpTrackerCodec.MAX_NUM_WANT)
    }
}

/**
 * Explicit tracker-discovery request. [announcePort] must be a real caller-owned peer endpoint;
 * this layer never substitutes the HTTP engine port or invents an inbound/self-announce port.
 */
class AceLiveUdpTrackerDiscoveryRequest(
    val swarmKey: AceLiveSwarmKey,
    trackers: List<String>,
    peerId: ByteArray,
    val announcePort: Int
) {
    val trackers: List<String> = trackers.toList()
    val peerId: ByteArray = peerId.copyOf()

    init {
        require(this.peerId.size == AceLiveUdpTrackerCodec.PEER_ID_BYTES) {
            "peerId must be exactly ${AceLiveUdpTrackerCodec.PEER_ID_BYTES} bytes"
        }
        require(announcePort in 1..65535) { "announcePort must be in 1..65535" }
    }
}

data class AceLiveUdpTrackerDiscoveryResult(
    val peers: List<AceLiveTcpPeerEndpoint>,
    val attemptedTrackers: Int,
    val failedTrackers: Int,
    val rejectedTrackers: Int
)

internal data class AceLiveUdpTrackerEndpoint(
    val host: String,
    val port: Int
)

/**
 * Clean-room BEP-15 adapter for independently verified Ace Live tracker discovery.
 *
 * It consumes only explicit `udp://` descriptor trackers and an already-resolved 20-byte live
 * swarm key. DHT/LSD, peer scoring, inbound listening, NAT mapping and proprietary identity are
 * deliberately separate concerns.
 */
class AceLiveUdpTrackerDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveUdpTrackerPolicy = AceLiveUdpTrackerPolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = DEFAULT_ADDRESS_RESOLVER
) {
    suspend fun discover(request: AceLiveUdpTrackerDiscoveryRequest): AceLiveUdpTrackerDiscoveryResult =
        withContext(ioDispatcher) {
            val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
            var attempted = 0
            var failed = 0
            var rejected = 0
            val deadlineNanos = System.nanoTime() + policy.discoveryBudgetMillis * NANOS_PER_MILLI

            val trackerValues = request.trackers
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(policy.maxTrackers)
                .toList()

            trackerLoop@ for (rawTracker in trackerValues) {
                currentCoroutineContext().ensureActive()
                if (remainingBudgetMillis(deadlineNanos) <= 0) break

                if (rawTracker.length > policy.maxTrackerUrlLength) {
                    rejected += 1
                    continue
                }
                val endpoint = parseTrackerEndpoint(rawTracker)
                if (endpoint == null) {
                    rejected += 1
                    continue
                }

                val addresses = try {
                    addressResolver(endpoint.host)
                        .asSequence()
                        .filter(::isAllowedTrackerAddress)
                        .distinctBy { it.hostAddress }
                        .take(policy.maxResolvedAddressesPerTracker)
                        .toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    emptyList()
                }
                if (addresses.isEmpty()) {
                    rejected += 1
                    continue
                }

                attempted += 1
                var trackerSucceeded = false
                var budgetExhausted = false
                for (address in addresses) {
                    currentCoroutineContext().ensureActive()
                    if (remainingBudgetMillis(deadlineNanos) <= 0) {
                        budgetExhausted = true
                        break
                    }

                    val discovered = try {
                        announce(
                            endpoint = InetSocketAddress(address, endpoint.port),
                            request = request,
                            deadlineNanos = deadlineNanos
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: DiscoveryBudgetExhaustedException) {
                        budgetExhausted = true
                        break
                    } catch (_: Exception) {
                        continue
                    }

                    trackerSucceeded = true
                    for (peer in discovered) {
                        if (!isAllowedPeerEndpoint(peer)) continue
                        val key = "${peer.host}:${peer.port}"
                        peers.putIfAbsent(key, peer)
                        if (peers.size >= policy.maxTotalPeers) break
                    }
                    break
                }

                if (!trackerSucceeded && !budgetExhausted) failed += 1
                if (budgetExhausted || peers.size >= policy.maxTotalPeers) break@trackerLoop
            }

            AceLiveUdpTrackerDiscoveryResult(
                peers = peers.values.toList(),
                attemptedTrackers = attempted,
                failedTrackers = failed,
                rejectedTrackers = rejected
            )
        }

    internal fun parseTrackerEndpoint(raw: String): AceLiveUdpTrackerEndpoint? {
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (!uri.scheme.equals("udp", ignoreCase = true)) return null
        if (uri.rawUserInfo != null || uri.rawQuery != null || uri.rawFragment != null) return null
        val host = uri.host?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val port = uri.port.takeIf { it in 1..65535 } ?: return null
        return AceLiveUdpTrackerEndpoint(host = host, port = port)
    }

    private fun isAllowedTrackerAddress(address: Inet4Address): Boolean =
        policy.allowNonGlobalTrackerAddresses || isGloballyRoutableIpv4(address)

    private fun isAllowedPeerEndpoint(peer: AceLiveTcpPeerEndpoint): Boolean {
        if (policy.allowNonGlobalPeerAddresses) return true
        val octets = peer.host.split('.').mapNotNull { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 }
        }
        if (octets.size != 4) return false
        val address = InetAddress.getByAddress(octets.map { it.toByte() }.toByteArray()) as Inet4Address
        return isGloballyRoutableIpv4(address)
    }

    private fun isGloballyRoutableIpv4(address: Inet4Address): Boolean {
        val value = ipv4ToLong(address.address)
        return SPECIAL_USE_IPV4_RANGES.none { cidr -> containsIpv4(cidr, value) }
    }

    private suspend fun announce(
        endpoint: InetSocketAddress,
        request: AceLiveUdpTrackerDiscoveryRequest,
        deadlineNanos: Long
    ): List<AceLiveTcpPeerEndpoint> {
        DatagramSocket().use { socket ->
            socket.connect(endpoint)
            val connectionId = connectWithRetry(socket, deadlineNanos)
            return announceWithRetry(socket, connectionId, request, deadlineNanos)
        }
    }

    private suspend fun connectWithRetry(
        socket: DatagramSocket,
        deadlineNanos: Long
    ): Long {
        val transactionId = randomInt()
        val request = AceLiveUdpTrackerCodec.encodeConnectRequest(transactionId)
        var lastTimeout: SocketTimeoutException? = null

        repeat(policy.maxRequestAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            socket.soTimeout = receiveTimeoutMillis(deadlineNanos)
            socket.send(DatagramPacket(request, request.size))
            try {
                val response = receiveBounded(socket)
                return AceLiveUdpTrackerCodec.decodeConnectResponse(response, transactionId)
            } catch (timeout: SocketTimeoutException) {
                lastTimeout = timeout
                currentCoroutineContext().ensureActive()
                if (attempt + 1 < policy.maxRequestAttempts) {
                    delayBeforeRetry(attempt, deadlineNanos)
                }
            }
        }

        throw lastTimeout ?: SocketTimeoutException("BEP-15 connect timed out")
    }

    private suspend fun announceWithRetry(
        socket: DatagramSocket,
        connectionId: Long,
        request: AceLiveUdpTrackerDiscoveryRequest,
        deadlineNanos: Long
    ): List<AceLiveTcpPeerEndpoint> {
        val transactionId = randomInt()
        val announceBytes = AceLiveUdpTrackerCodec.encodeAnnounceRequest(
            connectionId = connectionId,
            transactionId = transactionId,
            swarmKey = request.swarmKey,
            peerId = request.peerId,
            announcePort = request.announcePort,
            key = randomInt(),
            numWant = policy.numWant
        )
        var lastTimeout: SocketTimeoutException? = null

        repeat(policy.maxRequestAttempts) { attempt ->
            currentCoroutineContext().ensureActive()
            socket.soTimeout = receiveTimeoutMillis(deadlineNanos)
            socket.send(DatagramPacket(announceBytes, announceBytes.size))
            try {
                val response = receiveBounded(socket)
                return AceLiveUdpTrackerCodec.decodeAnnounceResponse(
                    bytes = response,
                    expectedTransactionId = transactionId,
                    maxPeers = policy.maxPeersPerTracker,
                    maxResponseBytes = policy.maxResponseBytes
                )
            } catch (timeout: SocketTimeoutException) {
                lastTimeout = timeout
                currentCoroutineContext().ensureActive()
                if (attempt + 1 < policy.maxRequestAttempts) {
                    delayBeforeRetry(attempt, deadlineNanos)
                }
            }
        }

        throw lastTimeout ?: SocketTimeoutException("BEP-15 announce timed out")
    }

    private suspend fun delayBeforeRetry(attempt: Int, deadlineNanos: Long) {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) throw DiscoveryBudgetExhaustedException()
        val multiplier = 1L shl attempt.coerceAtMost(10)
        val backoff = min(policy.retryBaseDelayMillis * multiplier, remaining)
        if (backoff > 0) delay(backoff)
        currentCoroutineContext().ensureActive()
    }

    private fun receiveTimeoutMillis(deadlineNanos: Long): Int {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) throw DiscoveryBudgetExhaustedException()
        return min(policy.requestTimeoutMillis.toLong(), remaining).coerceAtLeast(1).toInt()
    }

    private fun receiveBounded(socket: DatagramSocket): ByteArray {
        val buffer = ByteArray(policy.maxResponseBytes + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        if (packet.length > policy.maxResponseBytes) {
            throw AceLiveTrackerProtocolException("BEP-15 response exceeds local byte cap")
        }
        return packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
    }

    private fun remainingBudgetMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)

    private class DiscoveryBudgetExhaustedException : Exception()

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000
        const val IPV4_MASK: Long = 0xFFFF_FFFFL

        val SPECIAL_USE_IPV4_RANGES: List<AceLiveIpv4Cidr> = listOf(
            cidr(0, 0, 0, 0, 8),
            cidr(10, 0, 0, 0, 8),
            cidr(100, 64, 0, 0, 10),
            cidr(127, 0, 0, 0, 8),
            cidr(169, 254, 0, 0, 16),
            cidr(172, 16, 0, 0, 12),
            cidr(192, 0, 0, 0, 24),
            cidr(192, 0, 2, 0, 24),
            cidr(192, 31, 196, 0, 24),
            cidr(192, 52, 193, 0, 24),
            cidr(192, 88, 99, 0, 24),
            cidr(192, 168, 0, 0, 16),
            cidr(192, 175, 48, 0, 24),
            cidr(198, 18, 0, 0, 15),
            cidr(198, 51, 100, 0, 24),
            cidr(203, 0, 113, 0, 24),
            cidr(224, 0, 0, 0, 4),
            cidr(240, 0, 0, 0, 4)
        )

        val RANDOM = SecureRandom()
        val DEFAULT_RANDOM_INT: () -> Int = { RANDOM.nextInt() }
        val DEFAULT_ADDRESS_RESOLVER: (String) -> List<Inet4Address> = { host ->
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
        }

        fun cidr(a: Int, b: Int, c: Int, d: Int, prefixBits: Int): AceLiveIpv4Cidr =
            AceLiveIpv4Cidr(
                network = ((a.toLong() shl 24) or (b.toLong() shl 16) or (c.toLong() shl 8) or d.toLong()) and IPV4_MASK,
                prefixBits = prefixBits
            )

        fun ipv4ToLong(bytes: ByteArray): Long = bytes.fold(0L) { value, byte ->
            ((value shl 8) or (byte.toLong() and 0xFFL)) and IPV4_MASK
        }

        fun containsIpv4(cidr: AceLiveIpv4Cidr, value: Long): Boolean {
            val mask = (IPV4_MASK shl (32 - cidr.prefixBits)) and IPV4_MASK
            return (value and mask) == (cidr.network and mask)
        }
    }
}
