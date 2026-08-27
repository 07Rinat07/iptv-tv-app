package com.iptv.tv.core.p2p

import java.net.Inet4Address
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/** Explicit Mainline DHT bootstrap endpoint. No proprietary bootstrap list is embedded here. */
data class AceLiveDhtBootstrapNode(
    val host: String,
    val port: Int
) {
    init {
        require(host.isNotBlank()) { "bootstrap host must not be blank" }
        require(host.length <= 253) { "bootstrap host is too long" }
        require(port in 1..65535) { "bootstrap port must be in 1..65535" }
    }
}

/**
 * Local resource and network-safety bounds for BEP-5 discovery.
 *
 * [searchBranching] bounds concurrent KRPC requests. A small value still prevents one dead DHT
 * node from serializing the whole lookup, while the query, packet, peer, and absolute-time caps
 * keep the fan-out suitable for memory-constrained TV devices. [returnAfterPeers] optionally turns
 * the walker into a startup fast path: it still obeys [maxTotalPeers], but cancels remaining branches
 * after the requested number of valid peers has been collected.
 */
data class AceLiveDhtPolicy(
    val requestTimeoutMillis: Int = 2_000,
    val discoveryBudgetMillis: Long = 15_000,
    val searchBranching: Int = 4,
    val maxPacketBytes: Int = AceLiveDhtCodec.DEFAULT_MAX_PACKET_BYTES,
    val maxBootstrapNodes: Int = 8,
    val maxResolvedAddressesPerBootstrap: Int = 4,
    val maxQueries: Int = 64,
    val maxNodesPerResponse: Int = 64,
    val maxPeersPerResponse: Int = 64,
    val maxTotalPeers: Int = 256,
    val returnAfterPeers: Int? = null,
    val allowNonGlobalNodeAddresses: Boolean = false,
    val allowNonGlobalPeerAddresses: Boolean = false
) {
    init {
        require(requestTimeoutMillis in 100..30_000)
        require(discoveryBudgetMillis in requestTimeoutMillis.toLong()..120_000L)
        require(searchBranching in 1..64)
        require(maxPacketBytes in 512..65_507)
        require(maxBootstrapNodes in 1..64)
        require(maxResolvedAddressesPerBootstrap in 1..16)
        require(maxQueries in 1..512)
        require(maxNodesPerResponse in 1..AceLiveDhtCodec.DEFAULT_MAX_NODES)
        require(maxPeersPerResponse in 1..AceLiveDhtCodec.DEFAULT_MAX_PEERS)
        require(maxTotalPeers in 1..2_048)
        require(returnAfterPeers == null || returnAfterPeers in 1..maxTotalPeers)
    }
}

class AceLiveDhtDiscoveryRequest(
    val swarmKey: AceLiveSwarmKey,
    bootstrapNodes: List<AceLiveDhtBootstrapNode>,
    val localNodeId: AceLiveDhtNodeId = AceLiveDhtNodeId.random()
) {
    val bootstrapNodes: List<AceLiveDhtBootstrapNode> = bootstrapNodes.toList()

    init {
        require(this.bootstrapNodes.isNotEmpty()) { "At least one DHT bootstrap node is required" }
    }
}

data class AceLiveDhtDiscoveryResult(
    val peers: List<AceLiveTcpPeerEndpoint>,
    val queriesSent: Int,
    val failedQueries: Int,
    val rejectedEndpoints: Int,
    val warmRoutingSeedsUsed: Int = 0,
    val cacheHit: Boolean = false
)

/**
 * Clean-room Mainline DHT (BEP-5) `get_peers` discovery for an already-known Ace Live swarm key.
 *
 * The public request remains explicitly swarm-key based. The bounded iterative network walk is
 * shared internally with Content ID discovery without converting either identity into the other.
 *
 * Production callers may opt into a short process-wide positive-result reuse window. The global DHT
 * execution gate still owns serialization and heap safety; reuse only prevents the direct live path
 * and the concurrent metadata/refill paths from immediately repeating the same successful DHT walk
 * for the same swarm/bootstrap set. A cached batch is bypassed when any endpoint is currently inside
 * the existing swarm-scoped TCP-connect failure backoff, because filtering a stale cache hit must not
 * suppress a fresh bounded lookup for alternatives. While such failures are active, a startup DHT
 * fast path raises only its early peer-count threshold by the number of remembered failed endpoints.
 * That guarantees enough raw candidates can remain after eligibility filtering without changing the
 * discovery time, query, branching or total-peer caps. Empty results are never reused. Tests and
 * custom callers remain uncached unless they opt in explicitly.
 */
class AceLiveDhtDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    private val randomInt: () -> Int = AceDhtIterativeDiscovery.DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = AceDhtIterativeDiscovery.DEFAULT_ADDRESS_RESOLVER,
    private val reuseRecentResults: Boolean = false,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val routingMemory: AceDhtRoutingMemory? = null,
    private val connectFailureMemory: AceLiveTcpConnectFailureMemory =
        AceLiveTcpConnectFailureMemory.shared
) {
    private val delegate = delegateFor(policy)

    suspend fun discover(request: AceLiveDhtDiscoveryRequest): AceLiveDhtDiscoveryResult {
        val cacheKey = request.cacheKey()
        val swarmBytes = request.swarmKey.toByteArray()
        if (reuseRecentResults) {
            recentDhtResult(cacheKey, clockMillis())?.let { cached ->
                val entireCachedBatchStillEligible = cached.peers.all { endpoint ->
                    connectFailureMemory.isEligible(swarmBytes, endpoint)
                }
                if (entireCachedBatchStillEligible) {
                    return cached.copy(
                        queriesSent = 0,
                        failedQueries = 0,
                        rejectedEndpoints = 0,
                        warmRoutingSeedsUsed = 0,
                        cacheHit = true
                    )
                }
            }
        }

        val activeFailures = connectFailureMemory.activeFailureCount(swarmBytes)
        val normalEarlyReturn = policy.returnAfterPeers
        val effectiveEarlyReturn = if (normalEarlyReturn != null && activeFailures > 0) {
            minOf(policy.maxTotalPeers, normalEarlyReturn + activeFailures)
        } else {
            normalEarlyReturn
        }
        val discoveryDelegate = if (effectiveEarlyReturn == normalEarlyReturn) {
            delegate
        } else {
            delegateFor(policy.copy(returnAfterPeers = effectiveEarlyReturn))
        }
        val outcome = try {
            discoveryDelegate.discover(
                AceDhtLookupRequest(
                    targetBytes = swarmBytes,
                    bootstrapNodes = request.bootstrapNodes,
                    localNodeId = request.localNodeId,
                    encodeGetPeersQuery = { transactionId, nodeId ->
                        AceLiveDhtCodec.encodeGetPeersQuery(
                            transactionId = transactionId,
                            nodeId = nodeId,
                            swarmKey = request.swarmKey
                        )
                    }
                )
            )
        } finally {
            // The walker mutates only verified routing contacts. Persist them even when a rapid-zap
            // cancellation ends the current lookup after useful KRPC responses were already seen.
            routingMemory?.flush()
        }
        val result = AceLiveDhtDiscoveryResult(
            peers = outcome.peers,
            queriesSent = outcome.queriesSent,
            failedQueries = outcome.failedQueries,
            rejectedEndpoints = outcome.rejectedEndpoints,
            warmRoutingSeedsUsed = outcome.warmRoutingSeedsUsed,
            cacheHit = false
        )
        if (reuseRecentResults && result.peers.isNotEmpty()) {
            rememberDhtResult(cacheKey, result, clockMillis())
        }
        return result
    }

    private fun delegateFor(delegatePolicy: AceLiveDhtPolicy): AceDhtIterativeDiscovery =
        AceDhtIterativeDiscovery(
            ioDispatcher = ioDispatcher,
            policy = delegatePolicy,
            randomInt = randomInt,
            addressResolver = addressResolver,
            routingMemory = routingMemory
        )

    private fun AceLiveDhtDiscoveryRequest.cacheKey(): String = buildString {
        append(swarmKey.toHex())
        append('|')
        bootstrapNodes.forEachIndexed { index, node ->
            if (index > 0) append(',')
            append(node.host.lowercase())
            append(':')
            append(node.port)
        }
    }

    private companion object {
        const val RECENT_RESULT_TTL_MILLIS = 20_000L
        const val MAX_RECENT_RESULTS = 16
        val recentResultLock = Any()
        val recentResults = LinkedHashMap<String, CachedDhtResult>()

        fun recentDhtResult(key: String, nowMillis: Long): AceLiveDhtDiscoveryResult? =
            synchronized(recentResultLock) {
                pruneRecentResults(nowMillis)
                recentResults[key]?.result
            }

        fun rememberDhtResult(
            key: String,
            result: AceLiveDhtDiscoveryResult,
            nowMillis: Long
        ) = synchronized(recentResultLock) {
            pruneRecentResults(nowMillis)
            recentResults.remove(key)
            while (recentResults.size >= MAX_RECENT_RESULTS) {
                val eldestKey = recentResults.keys.firstOrNull() ?: break
                recentResults.remove(eldestKey)
            }
            recentResults[key] = CachedDhtResult(
                storedAtMillis = nowMillis,
                result = result.copy(peers = result.peers.toList(), cacheHit = false)
            )
        }

        fun pruneRecentResults(nowMillis: Long) {
            val iterator = recentResults.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                val age = if (nowMillis >= entry.value.storedAtMillis) {
                    nowMillis - entry.value.storedAtMillis
                } else {
                    Long.MAX_VALUE
                }
                if (age > RECENT_RESULT_TTL_MILLIS) iterator.remove()
            }
        }
    }

    private data class CachedDhtResult(
        val storedAtMillis: Long,
        val result: AceLiveDhtDiscoveryResult
    )
}
