package com.iptv.tv.core.p2p

import java.net.Inet4Address
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

class AceContentIdDhtDiscoveryRequest(
    val contentId: AceContentIdDhtKey,
    bootstrapNodes: List<AceLiveDhtBootstrapNode>,
    val localNodeId: AceLiveDhtNodeId = AceLiveDhtNodeId.random()
) {
    val bootstrapNodes: List<AceLiveDhtBootstrapNode> = bootstrapNodes.toList()

    init {
        require(this.bootstrapNodes.isNotEmpty()) { "At least one DHT bootstrap node is required" }
    }
}

data class AceContentIdDhtDiscoveryResult(
    val peers: List<AceLiveTcpPeerEndpoint>,
    val queriesSent: Int,
    val failedQueries: Int,
    val rejectedEndpoints: Int,
    val warmRoutingSeedsUsed: Int = 0
)

/**
 * Bounded Mainline DHT lookup for a verified Ace Content ID.
 *
 * The BEP-5 wire field is still named `info_hash`, but the target remains [AceContentIdDhtKey]
 * end-to-end. This class does not create a BitTorrent magnet, a BTIH or an [AceLiveSwarmKey].
 * Returned peers are only candidates for the later Ace transport-metadata bootstrap stage.
 */
class AceContentIdDhtDiscovery(
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    randomInt: () -> Int = AceDhtIterativeDiscovery.DEFAULT_RANDOM_INT,
    addressResolver: (String) -> List<Inet4Address> = AceDhtIterativeDiscovery.DEFAULT_ADDRESS_RESOLVER,
    routingMemory: AceDhtRoutingMemory? = null
) {
    private val delegate = AceDhtIterativeDiscovery(
        ioDispatcher = ioDispatcher,
        policy = policy,
        randomInt = randomInt,
        addressResolver = addressResolver,
        routingMemory = routingMemory
    )

    suspend fun discover(request: AceContentIdDhtDiscoveryRequest): AceContentIdDhtDiscoveryResult {
        val outcome = delegate.discover(
            AceDhtLookupRequest(
                targetBytes = request.contentId.toByteArray(),
                bootstrapNodes = request.bootstrapNodes,
                localNodeId = request.localNodeId,
                encodeGetPeersQuery = { transactionId, nodeId ->
                    AceContentIdDhtCodec.encodeGetPeersQuery(
                        transactionId = transactionId,
                        nodeId = nodeId,
                        contentId = request.contentId
                    )
                }
            )
        )
        return AceContentIdDhtDiscoveryResult(
            peers = outcome.peers,
            queriesSent = outcome.queriesSent,
            failedQueries = outcome.failedQueries,
            rejectedEndpoints = outcome.rejectedEndpoints,
            warmRoutingSeedsUsed = outcome.warmRoutingSeedsUsed
        )
    }
}
