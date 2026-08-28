package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.PriorityQueue
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.ThreadPoolExecutor
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
    val encodeGetPeersQuery: (ByteArray, AceLiveDhtNodeId) -> ByteArray,
    val collectWriteTokens: Boolean = false
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

internal class AceDhtWriteTokenCandidate(
    val nodeId: AceLiveDhtNodeId,
    val endpoint: AceLiveTcpPeerEndpoint,
    token: ByteArray
) {
    val token: ByteArray = token.copyOf()
}

internal data class AceDhtDiscoveryOutcome(
    val peers: List<AceLiveTcpPeerEndpoint>,
    val queriesSent: Int,
    val failedQueries: Int,
    val rejectedEndpoints: Int,
    val warmRoutingSeedsUsed: Int,
    val writeTokenCandidates: List<AceDhtWriteTokenCandidate> = emptyList()
)

internal fun aceDhtWarmRoutingSeedLimit(
    searchBranching: Int,
    maxWarmSeeds: Int = ACE_DHT_MAX_WARM_ROUTING_SEEDS
): Int {
    require(searchBranching > 0) { "DHT search branching must be positive" }
    require(maxWarmSeeds >= 0) { "DHT warm seed cap must be non-negative" }
    if (maxWarmSeeds == 0) return 0
    return if (searchBranching == 1) 1 else min(maxWarmSeeds, searchBranching - 1)
}

internal const val ACE_DHT_MAX_WARM_ROUTING_SEEDS = 4
internal const val ACE_DHT_MAX_WRITE_TOKEN_CANDIDATES = 64
internal const val ACE_DHT_MAX_CONCURRENT_BOOTSTRAP_RESOLUTIONS = 4
internal const val ACE_DHT_GLOBAL_RESOLVER_WORKERS = 4

/**
 * Shared bounded, concurrent BEP-5 iterative walker used by both verified Ace Live swarm keys and
 * Ace Content ID lookup targets. The target bytes are supplied explicitly by the caller, so this
 * layer never reclassifies a Content ID as a BitTorrent infohash or as an Ace Live swarm key.
 */
internal class AceDhtIterativeDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = DEFAULT_ADDRESS_RESOLVER,
    private val routingMemory: AceDhtRoutingMemory? = null,
    private val externalAddressObserver: AceDhtExternalAddressObserver = AceLiveDhtClientIdentity
) {
    suspend fun discover(request: AceDhtLookupRequest): AceDhtDiscoveryOutcome =
        withContext(ioDispatcher) {
            val deadlineNanos = System.nanoTime() + policy.discoveryBudgetMillis * NANOS_PER_MILLI
            val frontier = PriorityQueue(queryCandidateComparator(request.targetBytes))
            val queuedEndpoints = HashMap<String, AceLiveDhtNodeId?>()
            val queuedNodeIds = HashSet<AceLiveDhtNodeId>()
            val queriedEndpoints = HashSet<String>()
            val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
            val writeTokenCandidates = LinkedHashMap<String, AceDhtWriteTokenCandidate>()
            val completionPeerCount = policy.returnAfterPeers ?: policy.maxTotalPeers
            var rejected = 0
            var failed = 0
            var queries = 0

            // Known-ID contacts sort ahead of unresolved bootstraps. Bound the warm set below the
            // concurrent branch count so normal bootstrap always retains a first-wave lane in
            // production. Serial test/custom policies still try one verified contact before
            // falling back to bootstrap after the existing request timeout.
            val warmContacts = routingMemory?.recentContacts(
                aceDhtWarmRoutingSeedLimit(policy.searchBranching)
            ).orEmpty()
            var warmRoutingSeedsUsed = 0
            for (contact in warmContacts) {
                val key = endpointKey(contact.endpoint)
                if (!queuedEndpoints.containsKey(key) && queuedNodeIds.add(contact.nodeId)) {
                    queuedEndpoints[key] = contact.nodeId
                    frontier.add(
                        QueryCandidate(
                            endpoint = contact.endpoint,
                            nodeId = contact.nodeId,
                            fromRoutingMemory = true,
                            fromBootstrap = false
                        )
                    )
                }
            }

            coroutineScope {
                val inFlight = LinkedHashSet<Deferred<QueryCompletion>>()
                val bootstrapPrimaryFrontier = ArrayDeque<QueryCandidate>()
                val bootstrapExtraFrontier = ArrayDeque<QueryCandidate>()
                var bootstrapQueryLaunched = false
                // Bootstrap DNS runs beside the query loop. This lets verified warm contacts start
                // immediately. Resolution is pipelined under its own small cap so a caller's larger
                // bootstrap policy cannot create an unbounded burst of resolver work.
                val pendingBootstraps = ArrayDeque(
                    request.bootstrapNodes
                        .distinct()
                        .take(policy.maxBootstrapNodes)
                )
                val bootstrapInFlight = LinkedHashSet<Deferred<BootstrapResolution>>()

                fun startBootstrapResolution(
                    bootstrap: AceLiveDhtBootstrapNode
                ): Deferred<BootstrapResolution> = async {
                    try {
                        BootstrapResolution.Success(
                            bootstrap = bootstrap,
                            addresses = resolveBootstrap(bootstrap.host, deadlineNanos)
                                .asSequence()
                                .distinctBy { it.hostAddress }
                                .take(policy.maxResolvedAddressesPerBootstrap)
                                .toList()
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: DiscoveryBudgetExhaustedException) {
                        BootstrapResolution.BudgetExhausted
                    } catch (_: Exception) {
                        BootstrapResolution.Failed
                    }
                }

                fun fillBootstrapResolutionPipeline() {
                    if (queries >= policy.maxQueries) return
                    val concurrency = min(
                        ACE_DHT_MAX_CONCURRENT_BOOTSTRAP_RESOLUTIONS,
                        policy.maxBootstrapNodes
                    )
                    while (
                        bootstrapInFlight.size < concurrency &&
                        pendingBootstraps.isNotEmpty()
                    ) {
                        bootstrapInFlight += startBootstrapResolution(
                            pendingBootstraps.removeFirst()
                        )
                    }
                }

                var bootstrapResolutionDeferredForSerialWarmStart =
                    policy.searchBranching == 1 && frontier.isNotEmpty()
                if (!bootstrapResolutionDeferredForSerialWarmStart) {
                    fillBootstrapResolutionPipeline()
                }
                try {
                    while (
                        peers.size < completionPeerCount &&
                        (
                            inFlight.isNotEmpty() ||
                                (
                                    queries < policy.maxQueries &&
                                        (
                                            frontier.isNotEmpty() ||
                                                bootstrapPrimaryFrontier.isNotEmpty() ||
                                                bootstrapExtraFrontier.isNotEmpty() ||
                                                bootstrapInFlight.isNotEmpty() ||
                                                pendingBootstraps.isNotEmpty()
                                            )
                                    )
                            )
                    ) {
                        currentCoroutineContext().ensureActive()
                        if (remainingBudgetMillis(deadlineNanos) <= 0) break

                        while (inFlight.size < policy.searchBranching && queries < policy.maxQueries) {
                            val bootstrapStillPending =
                                !bootstrapQueryLaunched &&
                                    (
                                        bootstrapPrimaryFrontier.isNotEmpty() ||
                                            bootstrapInFlight.isNotEmpty() ||
                                            pendingBootstraps.isNotEmpty()
                                        )
                            val nonBootstrapCapacity = if (
                                bootstrapStillPending && policy.searchBranching > 1
                            ) {
                                policy.searchBranching - 1
                            } else {
                                policy.searchBranching
                            }
                            val nonBootstrapQueryLimit = if (bootstrapStillPending) {
                                (policy.maxQueries - 1).coerceAtLeast(0)
                            } else {
                                policy.maxQueries
                            }
                            val bootstrapResolutionPending =
                                bootstrapInFlight.isNotEmpty() || pendingBootstraps.isNotEmpty()
                            val bootstrapExtraCapacity = if (
                                bootstrapResolutionPending && policy.searchBranching > 1
                            ) {
                                policy.searchBranching - 1
                            } else {
                                policy.searchBranching
                            }
                            val candidate = when {
                                bootstrapPrimaryFrontier.isNotEmpty() ->
                                    bootstrapPrimaryFrontier.removeFirst()
                                inFlight.size < nonBootstrapCapacity &&
                                    queries < nonBootstrapQueryLimit &&
                                    frontier.isNotEmpty() ->
                                    frontier.remove()
                                inFlight.size < bootstrapExtraCapacity &&
                                    bootstrapExtraFrontier.isNotEmpty() ->
                                    bootstrapExtraFrontier.removeFirst()
                                else -> break
                            }
                            val candidateKey = endpointKey(candidate.endpoint)
                            if (!queriedEndpoints.add(candidateKey)) continue

                            queries += 1
                            if (candidate.fromBootstrap) bootstrapQueryLaunched = true
                            if (candidate.fromRoutingMemory) warmRoutingSeedsUsed += 1
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
                                    QueryCompletion.Failed(candidate)
                                }
                            }
                        }

                        if (
                            inFlight.isEmpty() &&
                            bootstrapInFlight.isEmpty() &&
                            frontier.isEmpty() &&
                            bootstrapPrimaryFrontier.isEmpty() &&
                            bootstrapExtraFrontier.isEmpty()
                        ) break
                        val selected = select<DiscoverySelection> {
                            // Kotlin select is biased. Register DNS first so an already-resolved
                            // bootstrap cannot be starved by a stream of fast warm-node responses.
                            if (queries < policy.maxQueries) {
                                bootstrapInFlight.forEach { pending ->
                                    pending.onAwait { outcome ->
                                        DiscoverySelection.Bootstrap(pending, outcome)
                                    }
                                }
                            }
                            inFlight.forEach { pending ->
                                pending.onAwait { outcome ->
                                    DiscoverySelection.Query(pending, outcome)
                                }
                            }
                        }

                        when (selected) {
                            is DiscoverySelection.Bootstrap -> {
                                bootstrapInFlight.remove(selected.pending)
                                fillBootstrapResolutionPipeline()
                                when (val resolution = selected.resolution) {
                                    BootstrapResolution.BudgetExhausted -> break
                                    BootstrapResolution.Failed -> Unit
                                    is BootstrapResolution.Success -> {
                                        var queuedForBootstrap = 0
                                        for (address in resolution.addresses) {
                                            if (!isAllowedNodeAddress(address)) {
                                                rejected += 1
                                                continue
                                            }
                                            val endpoint = AceLiveTcpPeerEndpoint(
                                                host = address.hostAddress ?: continue,
                                                port = resolution.bootstrap.port
                                            )
                                            val key = endpointKey(endpoint)
                                            if (!queuedEndpoints.containsKey(key)) {
                                                queuedEndpoints[key] = null
                                                val candidate = QueryCandidate(
                                                    endpoint = endpoint,
                                                    nodeId = null,
                                                    fromRoutingMemory = false,
                                                    fromBootstrap = true
                                                )
                                                if (queuedForBootstrap == 0) {
                                                    bootstrapPrimaryFrontier.addLast(candidate)
                                                } else {
                                                    bootstrapExtraFrontier.addLast(candidate)
                                                }
                                                queuedForBootstrap += 1
                                            }
                                        }
                                    }
                                }
                            }

                            is DiscoverySelection.Query -> {
                                inFlight.remove(selected.pending)
                                when (val completion = selected.completion) {
                                    QueryCompletion.BudgetExhausted -> break
                                    is QueryCompletion.Failed -> {
                                        failed += 1
                                        completion.candidate.routingContactOrNull()
                                            ?.let { contact -> routingMemory?.forget(contact) }
                                    }

                                    is QueryCompletion.Success -> {
                                        val candidate = completion.candidate
                                        val response = completion.response
                                        if (
                                            candidate.nodeId != null &&
                                            candidate.nodeId != response.remoteNodeId
                                        ) {
                                            failed += 1
                                            candidate.routingContactOrNull()
                                                ?.let { contact -> routingMemory?.forget(contact) }
                                            continue
                                        }

                                        routingMemory?.remember(
                                            AceLiveDhtNodeContact(
                                                nodeId = response.remoteNodeId,
                                                endpoint = candidate.endpoint
                                            )
                                        )
                                        queuedNodeIds.add(response.remoteNodeId)
                                        response.token
                                            ?.takeIf { request.collectWriteTokens }
                                            ?.takeIf {
                                                AceDhtNodeIdSecurity.isValidWriteTarget(
                                                    nodeId = response.remoteNodeId,
                                                    host = candidate.endpoint.host
                                                )
                                            }
                                            ?.let { token ->
                                                if (writeTokenCandidates.size < ACE_DHT_MAX_WRITE_TOKEN_CANDIDATES) {
                                                    writeTokenCandidates.putIfAbsent(
                                                        response.remoteNodeId.toString() + "@" + endpointKey(candidate.endpoint),
                                                        AceDhtWriteTokenCandidate(
                                                            nodeId = response.remoteNodeId,
                                                            endpoint = candidate.endpoint,
                                                            token = token
                                                        )
                                                    )
                                                }
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
                                            if (contact.nodeId in queuedNodeIds) continue
                                            val key = endpointKey(contact.endpoint)
                                            if (key in queriedEndpoints) continue
                                            val previousNodeId = queuedEndpoints[key]
                                            if (!queuedEndpoints.containsKey(key)) {
                                                queuedEndpoints[key] = contact.nodeId
                                                queuedNodeIds.add(contact.nodeId)
                                                frontier.add(
                                                    QueryCandidate(
                                                        endpoint = contact.endpoint,
                                                        nodeId = contact.nodeId,
                                                        fromRoutingMemory = false,
                                                        fromBootstrap = false
                                                    )
                                                )
                                            } else if (previousNodeId == null) {
                                                queuedEndpoints[key] = contact.nodeId
                                                queuedNodeIds.add(contact.nodeId)
                                                frontier.add(
                                                    QueryCandidate(
                                                        endpoint = contact.endpoint,
                                                        nodeId = contact.nodeId,
                                                        fromRoutingMemory = false,
                                                        fromBootstrap = false
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                if (bootstrapResolutionDeferredForSerialWarmStart) {
                                    bootstrapResolutionDeferredForSerialWarmStart = false
                                    fillBootstrapResolutionPipeline()
                                }
                            }
                        }
                    }
                } finally {
                    inFlight.forEach { pending -> pending.cancel() }
                    bootstrapInFlight.forEach { pending -> pending.cancel() }
                }
            }

            val orderedWriteCandidates = writeTokenCandidates.values
                .sortedWith { left, right ->
                    val distanceOrder = compareXorDistance(
                        left.nodeId.toByteArray(),
                        right.nodeId.toByteArray(),
                        request.targetBytes
                    )
                    if (distanceOrder != 0) distanceOrder
                    else endpointKey(left.endpoint).compareTo(endpointKey(right.endpoint))
                }

            AceDhtDiscoveryOutcome(
                peers = peers.values.toList(),
                queriesSent = queries,
                failedQueries = failed,
                rejectedEndpoints = rejected,
                warmRoutingSeedsUsed = warmRoutingSeedsUsed,
                writeTokenCandidates = orderedWriteCandidates
            )
        }

    private suspend fun resolveBootstrap(
        host: String,
        deadlineNanos: Long
    ): List<Inet4Address> {
        val remaining = remainingBudgetMillis(deadlineNanos)
        if (remaining <= 0) throw DiscoveryBudgetExhaustedException()
        val future = try {
            RESOLVER_EXECUTOR.submit<List<Inet4Address>> { addressResolver(host) }
        } catch (_: RejectedExecutionException) {
            return emptyList()
        }
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
            (future as? Runnable)?.let(RESOLVER_EXECUTOR::remove)
            RESOLVER_EXECUTOR.purge()
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
            runCatching {
                AceLiveDhtCodec.decodeExternalAddressObservation(
                    bytes = responseBytes,
                    expectedTransactionId = transactionId,
                    maxPacketBytes = policy.maxPacketBytes
                )
            }.getOrNull()?.let { observed ->
                externalAddressObserver.observe(
                    observedHost = observed.host,
                    responderHost = endpoint.host
                )
            }
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
        val nodeId: AceLiveDhtNodeId?,
        val fromRoutingMemory: Boolean,
        val fromBootstrap: Boolean
    ) {
        fun routingContactOrNull(): AceLiveDhtNodeContact? =
            nodeId?.let { rememberedNodeId ->
                AceLiveDhtNodeContact(
                    nodeId = rememberedNodeId,
                    endpoint = endpoint
                )
            }
    }

    private sealed interface QueryCompletion {
        data class Success(
            val candidate: QueryCandidate,
            val response: AceLiveDhtGetPeersResponse
        ) : QueryCompletion

        data class Failed(val candidate: QueryCandidate) : QueryCompletion
        data object BudgetExhausted : QueryCompletion
    }

    private sealed interface BootstrapResolution {
        data class Success(
            val bootstrap: AceLiveDhtBootstrapNode,
            val addresses: List<Inet4Address>
        ) : BootstrapResolution

        data object Failed : BootstrapResolution
        data object BudgetExhausted : BootstrapResolution
    }

    private sealed interface DiscoverySelection {
        data class Query(
            val pending: Deferred<QueryCompletion>,
            val completion: QueryCompletion
        ) : DiscoverySelection

        data class Bootstrap(
            val pending: Deferred<BootstrapResolution>,
            val resolution: BootstrapResolution
        ) : DiscoverySelection
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
        private const val MAX_QUEUED_RESOLVER_TASKS = 16
        private val RESOLVER_EXECUTOR = ThreadPoolExecutor(
            ACE_DHT_GLOBAL_RESOLVER_WORKERS,
            ACE_DHT_GLOBAL_RESOLVER_WORKERS,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(MAX_QUEUED_RESOLVER_TASKS),
            { runnable -> Thread(runnable, "ace-dht-dns").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )

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
