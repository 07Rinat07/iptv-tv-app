package com.iptv.tv.core.p2p

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.min

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
 * The adapter is discovery-only: it does not persist a routing table, call `announce_peer`, expose a
 * listening port, score peers, aggregate tracker results or infer a swarm key from `content_id`.
 */
class AceLiveDhtDiscovery(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val policy: AceLiveDhtPolicy = AceLiveDhtPolicy(),
    private val randomInt: () -> Int = DEFAULT_RANDOM_INT,
    private val addressResolver: (String) -> List<Inet4Address> = DEFAULT_ADDRESS_RESOLVER
) {
    suspend fun discover(request: AceLiveDhtDiscoveryRequest): AceLiveDhtDiscoveryResult =
        withContext(ioDispatcher) {
            val deadlineNanos = System.nanoTime() + policy.discoveryBudgetMillis * NANOS_PER_MILLI
            val targetBytes = request.swarmKey.toByteArray()
            val frontier = ArrayDeque<QueryCandidate>()
            val queuedEndpoints = HashSet<String>()
            val queriedEndpoints = HashSet<String>()
            val peers = LinkedHashMap<String, AceLiveTcpPeerEndpoint>()
            var rejected = 0
            var failed = 0
            var queries = 0

            for (bootstrap in request.bootstrapNodes.distinct().take(policy.maxBootstrapNodes)) {
                currentCoroutineContext().ensureActive()
                val addresses = try {
                    addressResolver(bootstrap.host)
                        .asSequence()
                        .distinctBy { it.hostAddress }
                        .take(policy.maxResolvedAddressesPerBootstrap)
                        .toList()
                } catch (cancelled: CancellationException) {
                    throw cancelled
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
                    if (queuedEndpoints.add(key)) {
                        frontier.addLast(QueryCandidate(endpoint = endpoint, nodeId = null))
                    }
                }
            }

            while (frontier.isNotEmpty() && queries < policy.maxQueries && peers.size < policy.maxTotalPeers) {
                currentCoroutineContext().ensureActive()
                if (remainingBudgetMillis(deadlineNanos) <= 0) break
                val candidate = frontier.removeFirst()
                val candidateKey = endpointKey(candidate.endpoint)
                if (!queriedEndpoints.add(candidateKey)) continue

                queries += 1
                val response = try {
                    queryGetPeers(
                        endpoint = candidate.endpoint,
                        request = request,
                        deadlineNanos = deadlineNanos
                    )
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: DiscoveryBudgetExhaustedException) {
                    break
                } catch (_: Exception) {
                    failed += 1
                    continue
                }

                for (peer in response.peers) {
                    if (!isAllowedPeerEndpoint(peer)) {
                        rejected += 1
                        continue
                    }
                    peers.putIfAbsent(endpointKey(peer), peer)
                    if (peers.size >= policy.maxTotalPeers) break
                }
                if (peers.size >= policy.maxTotalPeers) break

                val nextNodes = response.nodes
                    .asSequence()
                    .filter { contact -> endpointKey(contact.endpoint) !in queriedEndpoints }
                    .sortedWith { left, right ->
                        compareXorDistance(left.nodeId.toByteArray(), right.nodeId.toByteArray(), targetBytes)
                    }
                    .toList()

                for (contact in nextNodes) {
                    if (!isAllowedNodeEndpoint(contact.endpoint)) {
                        rejected += 1
                        continue
                    }
                    val key = endpointKey(contact.endpoint)
                    if (queuedEndpoints.add(key)) {
                        frontier.addLast(QueryCandidate(endpoint = contact.endpoint, nodeId = contact.nodeId))
                    }
                }
            }

            AceLiveDhtDiscoveryResult(
                peers = peers.values.toList(),
                queriesSent = queries,
                failedQueries = failed,
                rejectedEndpoints = rejected
            )
        }

    private suspend fun queryGetPeers(
        endpoint: AceLiveTcpPeerEndpoint,
        request: AceLiveDhtDiscoveryRequest,
        deadlineNanos: Long
    ): AceLiveDhtGetPeersResponse {
        currentCoroutineContext().ensureActive()
        val transactionId = nextTransactionId()
        val query = AceLiveDhtCodec.encodeGetPeersQuery(
            transactionId = transactionId,
            nodeId = request.localNodeId,
            swarmKey = request.swarmKey
        )
        val address = InetSocketAddress(endpoint.host, endpoint.port)

        DatagramSocket().use { socket ->
            socket.connect(address)
            socket.soTimeout = receiveTimeoutMillis(deadlineNanos)
            socket.send(DatagramPacket(query, query.size))
            currentCoroutineContext().ensureActive()
            val responseBytes = receiveBounded(socket)
            return AceLiveDhtCodec.decodeGetPeersResponse(
                bytes = responseBytes,
                expectedTransactionId = transactionId,
                maxPeers = policy.maxPeersPerResponse,
                maxNodes = policy.maxNodesPerResponse,
                maxPacketBytes = policy.maxPacketBytes
            )
        }
    }

    private fun receiveBounded(socket: DatagramSocket): ByteArray {
        val buffer = ByteArray(policy.maxPacketBytes + 1)
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (timeout: SocketTimeoutException) {
            throw timeout
        }
        if (packet.length > policy.maxPacketBytes) {
            throw AceLiveDhtProtocolException("KRPC response exceeds local packet cap")
        }
        return packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
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

    private data class Ipv4Cidr(val network: Long, val prefixBits: Int)

    private class DiscoveryBudgetExhaustedException : Exception()

    private companion object {
        const val NANOS_PER_MILLI: Long = 1_000_000
        const val IPV4_MASK: Long = 0xffff_ffffL
        val random = SecureRandom()
        val DEFAULT_RANDOM_INT: () -> Int = { random.nextInt() }
        val DEFAULT_ADDRESS_RESOLVER: (String) -> List<Inet4Address> = { host ->
            InetAddress.getAllByName(host).filterIsInstance<Inet4Address>()
        }

        val SPECIAL_USE_IPV4_RANGES = listOf(
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

        fun cidr(a: Int, b: Int, c: Int, d: Int, prefixBits: Int): Ipv4Cidr =
            Ipv4Cidr(
                network = ((a.toLong() shl 24) or (b.toLong() shl 16) or
                    (c.toLong() shl 8) or d.toLong()) and IPV4_MASK,
                prefixBits = prefixBits
            )

        fun ipv4ToLong(bytes: ByteArray): Long = bytes.fold(0L) { value, byte ->
            ((value shl 8) or (byte.toLong() and 0xffL)) and IPV4_MASK
        }

        fun containsIpv4(cidr: Ipv4Cidr, value: Long): Boolean {
            val mask = (IPV4_MASK shl (32 - cidr.prefixBits)) and IPV4_MASK
            return (value and mask) == (cidr.network and mask)
        }
    }
}
