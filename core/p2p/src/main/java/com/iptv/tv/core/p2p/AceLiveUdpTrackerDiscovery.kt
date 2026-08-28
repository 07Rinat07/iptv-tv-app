package com.iptv.tv.core.p2p

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URI
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

private data class AceLiveIpv4Cidr(
    val network: Long,
    val prefixBits: Int
)

/** Local safety bounds for descriptor-provided tracker/startup discovery. */
data class AceLiveUdpTrackerPolicy(
    val requestTimeoutMillis: Int = 2_000,
    val maxRequestAttempts: Int = 2,
    val retryBaseDelayMillis: Long = 200,
    val discoveryBudgetMillis: Long = 20_000,
    val maxTrackers: Int = 64,
    val maxResolvedAddressesPerTracker: Int = 4,
    val maxTrackerUrlLength: Int = 1_024,
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
 * Bounded Ace Live tracker/bootstrap discovery adapter.
 *
 * The class keeps its historical name because it is already wired through the P2P stack, but it now
 * consumes all independently documented discovery entries carried by an Ace transport: BEP-15 UDP
 * trackers, BEP-3 HTTP/HTTPS trackers, startup nodes and Ace metatrackers. Metatracker responses are
 * bounded and may add tracker URLs or startup nodes. Every returned endpoint still goes through the
 * normal TCP connection, Ace handshake, reputation and producer qualification path; no source is
 * trusted merely because it appeared in transport metadata.
 *
 * DHT/LSD, peer scoring, inbound listening and NAT mapping remain separate concerns.
 */
class AceLiveUdpTrackerDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveUdpTrackerPolicy = AceLiveUdpTrackerPolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = DEFAULT_ADDRESS_RESOLVER,
    private val httpClient: OkHttpClient = DEFAULT_HTTP_CLIENT
) {
    suspend fun discover(request: AceLiveUdpTrackerDiscoveryRequest): AceLiveUdpTrackerDiscoveryResult =
        withContext(ioDispatcher) {
            val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
            var attempted = 0
            var failed = 0
            var rejected = 0
            val deadlineNanos = System.nanoTime() + policy.discoveryBudgetMillis * NANOS_PER_MILLI

            val pending = ArrayDeque<String>()
            request.trackers
                .asSequence()
                .map(String::trim)
                .filter(String::isNotEmpty)
                .distinct()
                .take(policy.maxTrackers)
                .forEach(pending::addLast)
            val seen = HashSet<String>()
            var processedSources = 0

            sourceLoop@ while (
                pending.isNotEmpty() &&
                processedSources < policy.maxTrackers &&
                peers.size < policy.maxTotalPeers
            ) {
                currentCoroutineContext().ensureActive()
                if (remainingBudgetMillis(deadlineNanos) <= 0) break

                val rawSource = pending.removeFirst().trim()
                if (!seen.add(rawSource)) continue
                processedSources += 1
                if (rawSource.length > MAX_DISCOVERY_HINT_LENGTH) {
                    rejected += 1
                    continue
                }

                val startupNode = AceLiveDiscoveryHttpProtocol.parseStartupHint(rawSource)
                if (startupNode != null) {
                    attempted += 1
                    if (isAllowedPeerEndpoint(startupNode)) {
                        peers.putIfAbsent(endpointKey(startupNode), startupNode)
                    } else {
                        rejected += 1
                    }
                    continue
                }

                val metatrackerUrl = AceLiveDiscoveryHttpProtocol.parseMetatrackerHint(rawSource)
                if (metatrackerUrl != null) {
                    if (
                        metatrackerUrl.length > policy.maxTrackerUrlLength ||
                        !isAllowedHttpSource(metatrackerUrl)
                    ) {
                        rejected += 1
                        continue
                    }
                    attempted += 1
                    val snapshot = try {
                        fetchMetatracker(
                            metatrackerUrl = metatrackerUrl,
                            swarmKey = request.swarmKey,
                            deadlineNanos = deadlineNanos
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: DiscoveryBudgetExhaustedException) {
                        break@sourceLoop
                    } catch (_: Exception) {
                        failed += 1
                        continue
                    }
                    for (node in snapshot.startupNodes) {
                        AceLiveDiscoveryHttpProtocol.encodeStartupHint(node)?.let(pending::addLast)
                    }
                    for (tracker in snapshot.trackers) {
                        if (tracker.length <= policy.maxTrackerUrlLength) pending.addLast(tracker)
                    }
                    continue
                }

                if (AceLiveDiscoveryHttpProtocol.isHttpTracker(rawSource)) {
                    if (
                        rawSource.length > policy.maxTrackerUrlLength ||
                        !isAllowedHttpSource(rawSource)
                    ) {
                        rejected += 1
                        continue
                    }
                    attempted += 1
                    val discovered = try {
                        announceHttp(
                            rawTracker = rawSource,
                            request = request,
                            deadlineNanos = deadlineNanos
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: DiscoveryBudgetExhaustedException) {
                        break@sourceLoop
                    } catch (_: Exception) {
                        failed += 1
                        continue
                    }
                    addEligiblePeers(peers, discovered)
                    continue
                }

                if (rawSource.length > policy.maxTrackerUrlLength) {
                    rejected += 1
                    continue
                }
                val endpoint = parseTrackerEndpoint(rawSource)
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
                        announceUdp(
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
                    addEligiblePeers(peers, discovered)
                    break
                }

                if (!trackerSucceeded && !budgetExhausted) failed += 1
                if (budgetExhausted) break@sourceLoop
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

    private fun addEligiblePeers(
        target: LinkedHashMap<String, AceLiveTcpPeerEndpoint>,
        discovered: List<AceLiveTcpPeerEndpoint>
    ) {
        for (peer in discovered) {
            if (!isAllowedPeerEndpoint(peer)) continue
            target.putIfAbsent(endpointKey(peer), peer)
            if (target.size >= policy.maxTotalPeers) break
        }
    }

    private fun endpointKey(peer: AceLiveTcpPeerEndpoint): String =
        "${peer.host.lowercase()}:${peer.port}"

    private fun isAllowedHttpSource(rawUrl: String): Boolean {
        val host = AceLiveDiscoveryHttpProtocol.httpHost(rawUrl) ?: return false
        return try {
            addressResolver(host)
                .asSequence()
                .filter(::isAllowedTrackerAddress)
                .take(policy.maxResolvedAddressesPerTracker)
                .any()
        } catch (_: Exception) {
            false
        }
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

    private suspend fun announceUdp(
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

    private suspend fun announceHttp(
        rawTracker: String,
        request: AceLiveUdpTrackerDiscoveryRequest,
        deadlineNanos: Long
    ): List<AceLiveTcpPeerEndpoint> {
        val url = AceLiveDiscoveryHttpProtocol.buildHttpTrackerAnnounceUrl(
            rawUrl = rawTracker,
            swarmKey = request.swarmKey,
            peerId = request.peerId,
            announcePort = request.announcePort,
            key = randomInt(),
            numWant = policy.numWant
        ) ?: throw AceLiveTrackerProtocolException("Invalid HTTP tracker URL")
        val response = fetchHttpBytes(url, deadlineNanos)
        return AceLiveDiscoveryHttpProtocol.decodeHttpTrackerResponse(
            bytes = response,
            maxPeers = policy.maxPeersPerTracker,
            maxResponseBytes = policy.maxResponseBytes
        )
    }

    private suspend fun fetchMetatracker(
        metatrackerUrl: String,
        swarmKey: AceLiveSwarmKey,
        deadlineNanos: Long
    ): AceLiveMetatrackerSnapshot {
        val cacheKey = metatrackerUrl + "|" + swarmKey.toHex()
        val now = System.currentTimeMillis()
        recentMetatracker(cacheKey, now)?.let { return it }
        val requestUrl = AceLiveDiscoveryHttpProtocol.buildMetatrackerRequestUrl(
            rawUrl = metatrackerUrl,
            swarmKey = swarmKey
        ) ?: throw AceLiveTrackerProtocolException("Invalid Ace metatracker URL")
        val response = fetchHttpBytes(requestUrl, deadlineNanos)
        val snapshot = AceLiveDiscoveryHttpProtocol.decodeMetatrackerResponse(
            bytes = response,
            maxEntries = policy.maxTrackers,
            maxStringLength = policy.maxTrackerUrlLength
        )
        val ttlSeconds = snapshot.intervalSeconds
            ?.coerceIn(MIN_METATRACKER_CACHE_SECONDS, MAX_METATRACKER_CACHE_SECONDS)
            ?: DEFAULT_METATRACKER_CACHE_SECONDS
        rememberMetatracker(
            key = cacheKey,
            snapshot = snapshot,
            expiresAtMillis = now + ttlSeconds * 1_000L,
            nowMillis = now
        )
        return snapshot
    }

    private suspend fun fetchHttpBytes(
        url: String,
        deadlineNanos: Long
    ): ByteArray {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0L) throw DiscoveryBudgetExhaustedException()
        val timeoutMillis = min(policy.requestTimeoutMillis.toLong(), remaining).coerceAtLeast(1L)
        val boundedClient = httpClient.newBuilder()
            .connectTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
        val httpRequest = Request.Builder().url(url).get().build()
        return suspendCancellableCoroutine { continuation ->
            val call = boundedClient.newCall(httpRequest)
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, error: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use { safeResponse ->
                            if (!safeResponse.isSuccessful) {
                                throw IOException("HTTP discovery returned ${safeResponse.code}")
                            }
                            val body = safeResponse.body
                                ?: throw IOException("HTTP discovery returned an empty response body")
                            body.byteStream().use { input ->
                                val output = ByteArrayOutputStream(minOf(4_096, policy.maxResponseBytes))
                                val buffer = ByteArray(4_096)
                                var total = 0
                                while (true) {
                                    val read = input.read(buffer)
                                    if (read < 0) break
                                    total += read
                                    if (total > policy.maxResponseBytes) {
                                        throw AceLiveTrackerProtocolException(
                                            "HTTP discovery response exceeds local byte cap"
                                        )
                                    }
                                    output.write(buffer, 0, read)
                                }
                                if (continuation.isActive) continuation.resume(output.toByteArray())
                            }
                        }
                    } catch (error: Throwable) {
                        if (continuation.isActive) continuation.resumeWithException(error)
                    }
                }
            })
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
        const val MAX_DISCOVERY_HINT_LENGTH = 4_096
        const val MIN_METATRACKER_CACHE_SECONDS = 15L
        const val DEFAULT_METATRACKER_CACHE_SECONDS = 300L
        const val MAX_METATRACKER_CACHE_SECONDS = 3_600L
        const val MAX_METATRACKER_CACHE_ENTRIES = 32

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
        val DEFAULT_HTTP_CLIENT = OkHttpClient.Builder()
            .connectTimeout(2_000, TimeUnit.MILLISECONDS)
            .readTimeout(2_000, TimeUnit.MILLISECONDS)
            .writeTimeout(2_000, TimeUnit.MILLISECONDS)
            .callTimeout(2_000, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        val metatrackerCacheLock = Any()
        val metatrackerCache = LinkedHashMap<String, CachedMetatracker>()

        fun recentMetatracker(key: String, nowMillis: Long): AceLiveMetatrackerSnapshot? =
            synchronized(metatrackerCacheLock) {
                pruneMetatrackerCache(nowMillis)
                metatrackerCache[key]?.snapshot
            }

        fun rememberMetatracker(
            key: String,
            snapshot: AceLiveMetatrackerSnapshot,
            expiresAtMillis: Long,
            nowMillis: Long
        ) = synchronized(metatrackerCacheLock) {
            pruneMetatrackerCache(nowMillis)
            metatrackerCache.remove(key)
            while (metatrackerCache.size >= MAX_METATRACKER_CACHE_ENTRIES) {
                val eldest = metatrackerCache.keys.firstOrNull() ?: break
                metatrackerCache.remove(eldest)
            }
            metatrackerCache[key] = CachedMetatracker(expiresAtMillis, snapshot)
        }

        fun pruneMetatrackerCache(nowMillis: Long) {
            val iterator = metatrackerCache.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (nowMillis >= entry.value.expiresAtMillis) iterator.remove()
            }
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

    private data class CachedMetatracker(
        val expiresAtMillis: Long,
        val snapshot: AceLiveMetatrackerSnapshot
    )
}
