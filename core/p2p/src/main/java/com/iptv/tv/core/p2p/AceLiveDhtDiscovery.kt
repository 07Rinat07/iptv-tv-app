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

/** Local resource and network-safety bounds for BEP-5 discovery. */
data class AceLiveDhtPolicy(
    val requestTimeoutMillis: Int = 2_000,
    val discoveryBudgetMillis: Long = 15_000,
    val maxPacketBytes: Int = AceLiveDhtCodec.DEFAULT_MAX_PACKET_BYTES,
    val maxBootstrapNodes: Int = 8,
    val maxResolvedAddressesPerBootstrap: Int = 4,
    val maxQueries: Int = 64,
    val maxNodesPerResponse: Int = 64,
    val maxPeersPerResponse: Int = 64,
    val maxTotalPeers: Int = 256,
    val allowNonGlobalNodeAddresses: Boolean = false,
    val allowNonGlobalPeerAddresses: Boolean = false
) {
    init {
        require(requestTimeoutMillis in 100..30_000)
        require(discoveryBudgetMillis in requestTimeoutMillis.toLong()..120_000L)
        require(maxPacketBytes in 512..65_507)
        require(maxBootstrapNodes in 1..64)
        require(maxResolvedAddressesPerBootstrap in 1..16)
        require(maxQueries in 1..512)
        require(maxNodesPerResponse in 1..AceLiveDhtCodec.DEFAULT_MAX_NODES)
        require(maxPeersPerResponse in 1..AceLiveDhtCodec.DEFAULT_MAX_PEERS)
        require(maxTotalPeers in 1..2_048)
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
    val rejectedEndpoints: Int
)

/**
 * Clean-room Mainline DHT (BEP-5) `get_peers` discovery for an already-known Ace Live swarm key.
 *
 * The public request remains explicitly swarm-key based. The bounded iterative network walk is
 * shared internally with Content ID discovery without converting either identity into the other.
 *
 * Production callers may opt into a short process-wide result reuse window. The global DHT
 * execution gate still owns serialization and heap safety; reuse only prevents the direct live path
 * and the concurrent metadata/refill paths from immediately repeating the same completed DHT walk
 * for the same swarm/bootstrap set. The reuse window intentionally covers one bounded metadata-peer
 * probing phase, after which normal discovery may refresh the swarm again. Tests and custom callers
 * remain uncached unless they opt in explicitly.
 */
class AceLiveDhtDiscovery(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    randomInt: () -> Int = AceDhtIterativeDiscovery.DEFAULT_RANDOM_INT,
    addressResolver: (String) -> List<Inet4Address> = AceDhtIterativeDiscovery.DEFAULT_ADDRESS_RESOLVER,
    private val reuseRecentResults: Boolean = false,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val delegate = AceDhtIterativeDiscovery(
        ioDispatcher = ioDispatcher,
        policy = policy,
        randomInt = randomInt,
        addressResolver = addressResolver
    )

    suspend fun discover(request: AceLiveDhtDiscoveryRequest): AceLiveDhtDiscoveryResult {
        val cacheKey = request.cacheKey()
        if (reuseRecentResults) {
            recentDhtResult(cacheKey, clockMillis())?.let { cached -> return cached }
        }

        val outcome = delegate.discover(
            AceDhtLookupRequest(
                targetBytes = request.swarmKey.toByteArray(),
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
        val result = AceLiveDhtDiscoveryResult(
            peers = outcome.peers,
            queriesSent = outcome.queriesSent,
            failedQueries = outcome.failedQueries,
            rejectedEndpoints = outcome.rejectedEndpoints
        )
        if (reuseRecentResults) {
            rememberDhtResult(cacheKey, result, clockMillis())
        }
        return result
    }

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
                result = result.copy(peers = result.peers.toList())
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
