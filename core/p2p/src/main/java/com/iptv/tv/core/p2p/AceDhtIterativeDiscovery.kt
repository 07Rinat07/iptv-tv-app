package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.PriorityQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.min

internal class AceDhtLookupRequest(
    targetBytes: ByteArray,
    bootstrapNodes: List<AceLiveDhtBootstrapNode>,
    val localNodeId: AceLiveDhtNodeId,
    val encodeGetPeersQuery: (ByteArray, AceLiveDhtNodeId) -> ByteArray
) {
    val targetBytes: ByteArray = targetBytes.copyOf()
    val bootstrapNodes: List<AceLiveDhtBootstrapNode> = bootstrapNodes.toList()

    init {
        require(this.targetBytes.size == AceLiveDhtNodeId.BYTES) {
            "DHT lookup target must be exactly ${AceLiveDhtNodeId.BYTES} bytes"
        }
        require(this.bootstrapNodes.isNotEmpty()) { "At least one DHT bootstrap node is required" }
    }
}

internal data class AceDhtDiscoveryOutcome(
    val peers: List<AceLiveTcpPeerEndpoint>,
    val queriesSent: Int,
    val failedQueries: Int,
    val rejectedEndpoints: Int
)

/**
 * Shared bounded, concurrent BEP-5 iterative walker used by both verified Ace Live swarm keys and
 * Ace Content ID lookup targets. The target bytes are supplied explicitly by the caller, so this
 * layer never reclassifies a Content ID as a BitTorrent infohash or as an Ace Live swarm key.
 */
internal class AceDhtIterativeDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = DEFAULT_ADDRESS_RESOLVER
) {
    suspend fun discover(request: AceDhtLookupRequest): AceDhtDiscoveryOutcome =
        withContext(ioDispatcher) {
            val deadlineNanos = System.nanoTime() + policy.discoveryBudgetMillis * NANOS_PER_MILLI
            val frontier = PriorityQueue(queryCandidateComparator(request.targetBytes))
            val queuedEndpoints = HashMap<String, AceLiveDhtNodeId?>()
            val queriedEndpoints = HashSet<String>()
            val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
            val completionPeerCount = policy.returnAfterPeers ?: policy.maxTotalPeers
            var rejected = 0
            var failed = 0
            var queries = 0

            for (bootstrap in request.bootstrapNodes.distinct().take(policy.maxBootstrapNodes)) {
                currentCoroutineContext().ensureActive()
                if (remainingBudgetMillis(deadlineNanos) <= 0) break
                val addresses = try {
                    resolveBootstrap(bootstrap.host, deadlineNanos)
                        .asSequence()
                        .distinctBy { it.hostAddress }
                        .take(policy.maxResolvedAddressesPerBootstrap)
                        .toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DiscoveryBudgetExhaustedException) {
                    break
                } catch (_: Exception) {
                    emptyList()
                }
                for (address in addresses) {
                    if (!isAllowedNodeAddress(address)) {
                        rejected += 1
                        continue
                    }
                    val endpoint = AceLiveTcpPeerEndpoint(address.hostAddress ?: continue, bootstrap.port)
                    val key = endpointKey(endpoint)
                    if (!queuedEndpoints.containsKey(key)) {
                        queuedEndpoints[key] = null
                        frontier.add(QueryCandidate(endpoint = endpoint, nodeId = null))
                    }
                }
            }

            coroutineScope {
                val inFlight = LinkedHashSet<Deferred<QueryCompletion>>()
                try {
                    while (
                        peers.size < completionPeerCount &&
                        (inFlight.isNotEmpty() || (frontier.isNotEmpty() && queries < policy.maxQueries))
                    ) {
                        currentCoroutineContext().ensureActive()
                        if (remainingBudgetMillis(deadlineNanos) <= 0) break

                        while (
                            inFlight.size < policy.searchBranching &&
                            queries < policy.maxQueries &&
                            frontier.isNotEmpty()
                        ) {
                            val candidate = frontier.remove()
                            val candidateKey = endpointKey(candidate.endpoint)
                            if (!queriedEndpoints.add(candidateKey)) continue

                            queries += 1
                            inFlight += async {
                                try {
                                    QueryCompletion.Success(
                                        candidate = candidate,
                                        response = queryGetPeers(
                                            endpoint = candidate.endpoint,
                                            request = request,
                                            deadlineNanos = deadlineNanos
                                        )
                                    )
                                } catch (cancelled: CancellationException) {
                                    throw cancelled
                                } catch (_: DiscoveryBudgetExhaustedException) {
                                    QueryCompletion.BudgetExhausted
                                } catch (_: Exception) {
                                    QueryCompletion.Failed
                                }
                            }
                        }

                        if (inFlight.isEmpty()) break
                        val (completed, completion) = select<Pair<Deferred<QueryCompletion>, QueryCompletion>> {
                            inFlight.forEach { pending ->
                                pending.onAwait { outcome -> pending to outcome }
                            }
                        }
                        inFlight.remove(completed)

                        when (completion) {
                            QueryCompletion.BudgetExhausted -> break
                            QueryCompletion.Failed -> {
                                failed += 1
                                continue
                            }
                            is QueryCompletion.Success -> {
                                val candidate = completion.candidate
                                val response = completion.response
                                if (candidate.nodeId != null && candidate.nodeId != response.remoteNodeId) {
                                    failed += 1
                                    continue
                                }

                                for (peer in response.peers) {
                                    if (!isAllowedPeerEndpoint(peer)) {
                                        rejected += 1
                                        continue
                                    }
                                    peers.putIfAbsent(endpointKey(peer), peer)
                                    if (peers.size >= completionPeerCount) break
                                }
                                if (peers.size >= completionPeerCount) break

                                for (contact in response.nodes) {
                                    if (!isAllowedNodeEndpoint(contact.endpoint)) {
                                        rejected += 1
                                        continue
                                    }
                                    val key = endpointKey(contact.endpoint)
                                    if (key in queriedEndpoints) continue
                                    val previousNodeId = queuedEndpoints[key]
                                    if (!queuedEndpoints.containsKey(key)) {
                                        queuedEndpoints[key] = contact.nodeId
                                        frontier.add(QueryCandidate(contact.endpoint, contact.nodeId))
                                    } else if (previousNodeId == null) {
                                        queuedEndpoints[key] = contact.nodeId
                                        frontier.add(QueryCandidate(contact.endpoint, contact.nodeId))
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    inFlight.forEach { pending -> pending.cancel() }
                }
            }

            AceDhtDiscoveryOutcome(
                peers = peers.values.toList(),
                queriesSent = queries,
                failedQueries = failed,
                rejectedEndpoints = rejected
            )
        }

    private suspend fun resolveBootstrap(
        host: String,
        deadlineNanos: Long
    ): List<Inet4Address> {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) throw DiscoveryBudgetExhaustedException()
        val future = RESOLVER_EXECUTOR.submit<List<Inet4Address>> { addressResolver(host) }
        return try {
            runInterruptible {
                future.get(remaining, TimeUnit.MILLISECONDS)
            }
        } catch (_: TimeoutException) {
            emptyList()
        } catch (_: ExecutionException) {
            emptyList()
        } finally {
            if (!future.isDone) future.cancel(true)
        }
    }

    private suspend fun queryGetPeers(
        endpoint: AceLiveTcpPeerEndpoint,
        request: AceDhtLookupRequest,
        deadlineNanos: Long
    ): AceLiveDhtGetPeersResponse {
        currentCoroutineContext().ensureActive()
        val transactionId = nextTransactionId()
        val query = request.encodeGetPeersQuery(transactionId, request.localNodeId)
        val address = InetSocketAddress(endpoint.host, endpoint.port)

        DatagramSocket().use { socket ->
            socket.connect(address)
            socket.soTimeout = receiveTimeoutMillis(deadlineNanos)
            socket.send(DatagramPacket(query, query.size))
            currentCoroutineContext().ensureActive()
            val responseBytes = receiveBounded(socket)
            currentCoroutineContext().ensureActive()
            return AceLiveDhtCodec.decodeGetPeersResponse(
                bytes = responseBytes,
                expectedTransactionId = transactionId,
                maxPeers = policy.maxPeersPerResponse,
                maxNodes = policy.maxNodesPerResponse,
                maxPacketBytes = policy.maxPacketBytes
            )
        }
    }

    private suspend fun receiveBounded(socket: DatagramSocket): ByteArray =
        suspendCancellableCoroutine { continuation ->
            val buffer = ByteArray(policy.maxPacketBytes + 1)
            val packet = DatagramPacket(buffer, buffer.size)

            continuation.invokeOnCancellation {
                runCatching { socket.close() }
            }

            try {
                socket.receive(packet)
                if (packet.length > policy.maxPacketBytes) {
                    throw AceLiveDhtProtocolException("KRPC response exceeds local packet cap")
                }
                val bytes = packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                if (continuation.isActive) continuation.resume(bytes)
            } catch (error: Throwable) {
                if (continuation.isActive) continuation.resumeWithException(error)
            }
        }

    private fun nextTransactionId(): ByteArray {
        val value = randomInt()
        return byteArrayOf((value ushr 8).toByte(), value.toByte())
    }

    private fun receiveTimeoutMillis(deadlineNanos: Long): Int {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) throw DiscoveryBudgetExhaustedException()
        return min(policy.requestTimeoutMillis.toLong(), remaining).coerceAtLeast(1).toInt()
    }

    private fun remainingBudgetMillis(deadlineNanos: Long): Long =
        ((deadlineNanos - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)

    private fun queryCandidateComparator(target: ByteArray): Comparator<QueryCandidate> =
        Comparator { left, right ->
            val leftId = left.nodeId
            val rightId = right.nodeId
            when {
                leftId == null && rightId == null -> endpointKey(left.endpoint).compareTo(endpointKey(right.endpoint))
                leftId == null -> 1
                rightId == null -> -1
                else -> {
                    val distanceOrder = compareXorDistance(
                        leftId.toByteArray(),
                        rightId.toByteArray(),
                        target
                    )
                    if (distanceOrder != 0) distanceOrder
                    else endpointKey(left.endpoint).compareTo(endpointKey(right.endpoint))
                }
            }
        }

    private fun isAllowedNodeEndpoint(endpoint: AceLiveTcpPeerEndpoint): Boolean {
        val address = parseIpv4(endpoint.host) ?: return false
        return isAllowedNodeAddress(address)
    }

    private fun isAllowedNodeAddress(address: Inet4Address): Boolean =
        policy.allowNonGlobalNodeAddresses || isGloballyRoutableIpv4(address)

    private fun isAllowedPeerEndpoint(endpoint: AceLiveTcpPeerEndpoint): Boolean {
        if (policy.allowNonGlobalPeerAddresses) return true
        val address = parseIpv4(endpoint.host) ?: return false
        return isGloballyRoutableIpv4(address)
    }

    private fun parseIpv4(host: String): Inet4Address? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val octets = ByteArray(4)
        for (index in parts.indices) {
            val value = parts[index].toIntOrNull()?.takeIf { it in 0..255 } ?: return null
            octets[index] = value.toByte()
        }
        return InetAddress.getByAddress(octets) as? Inet4Address
    }

    private fun isGloballyRoutableIpv4(address: Inet4Address): Boolean {
        val value = ipv4ToLong(address.address)
        return SPECIAL_USE_IPV4_RANGES.none { cidr -> containsIpv4(cidr, value) }
    }

    private fun endpointKey(endpoint: AceLiveTcpPeerEndpoint): String = "${endpoint.host}:${endpoint.port}"

    private fun compareXorDistance(left: ByteArray, right: ByteArray, target: ByteArray): Int {
        for (index in target.indices) {
            val leftDistance = (left[index].toInt() xor target[index].toInt()) and 0xff
            val rightDistance = (right[index].toInt() xor target[index].toInt()) and 0xff
            if (leftDistance != rightDistance) return leftDistance.compareTo(rightDistance)
        }
        return 0
    }

    private data class QueryCandidate(
        val endpoint: AceLiveTcpPeerEndpoint,
        val nodeId: AceLiveDhtNodeId?
    )

    private sealed interface QueryCompletion {
        data class Success(
            val candidate: QueryCandidate,
            val response: AceLiveDhtGetPeersResponse
        ) : QueryCompletion

        data object Failed : QueryCompletion
        data object BudgetExhausted : QueryCompletion
    }

    private data class Ipv4Cidr(val network: Long, val prefixBits: Int)

    private class DiscoveryBudgetExhaustedException : Exception()

    companion object {
        private const val NANOS_PER_MILLI: Long = 1_000_000
        private const val IPV4_MASK: Long = 0xffff_ffffL
        private val random = SecureRandom()
        internal val DEFAULT_RANDOM_INT: () -> Int = { random.nextInt() }
        internal val DEFAULT_ADDRESS_RESOLVER: (String) -> List<Inet4Address> = { host ->
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
        }
        private val RESOLVER_EXECUTOR = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "ace-dht-dns").apply { isDaemon = true }
        }

        private val SPECIAL_USE_IPV4_RANGES = listOf(
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

        private fun cidr(a: Int, b: Int, c: Int, d: Int, prefixBits: Int): Ipv4Cidr =
            Ipv4Cidr(
                network = ((a.toLong() shl 24) or (b.toLong() shl 16) or
                    (c.toLong() shl 8) or d.toLong()) and IPV4_MASK,
                prefixBits = prefixBits
            )

        private fun ipv4ToLong(bytes: ByteArray): Long = bytes.fold(0L) { value, byte ->
            ((value shl 8) or (byte.toLong() and 0xffL)) and IPV4_MASK
        }

        private fun containsIpv4(cidr: Ipv4Cidr, value: Long): Boolean {
            val mask = (IPV4_MASK shl (32 - cidr.prefixBits)) and IPV4_MASK
            return (value and mask) == (cidr.network and mask)
        }
    }
}
