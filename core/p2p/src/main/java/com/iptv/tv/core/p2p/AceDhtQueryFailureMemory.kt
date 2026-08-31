package com.iptv.tv.core.p2p

/**
 * Short process-level negative memory for KRPC DHT node endpoints that just failed a query.
 *
 * Iterative discovery walks are intentionally short-lived. Without this boundary, a dead node
 * learned from one responder can be reintroduced by another responder in the next probe and spend
 * the same bounded query slot and timeout again. Entries are endpoint-global rather than swarm-
 * scoped because KRPC reachability belongs to the DHT node, not the lookup target.
 *
 * Bootstrap endpoints deliberately bypass this memory in [AceDhtIterativeDiscovery], so a failed
 * learned/warm contact can never suppress the independent bootstrap fallback. No DHT request,
 * discovery, branching, peer, TCP-connect, handshake or playback budget is widened here.
 */
class AceDhtQueryFailureMemory(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val backoffMillis: Long = DEFAULT_BACKOFF_MILLIS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES
) {
    private val lock = Any()
    private val retryNotBefore = LinkedHashMap<EndpointKey, Long>()

    init {
        require(backoffMillis > 0L) { "DHT query failure backoff must be positive" }
        require(maxEntries in 1..MAX_ALLOWED_ENTRIES) {
            "DHT query failure maxEntries must be in 1..$MAX_ALLOWED_ENTRIES"
        }
    }

    fun recordFailure(
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ) = synchronized(lock) {
        val key = key(endpoint)
        pruneLocked(nowMillis)
        retryNotBefore.remove(key)
        while (retryNotBefore.size >= maxEntries) {
            val eldest = retryNotBefore.keys.firstOrNull() ?: break
            retryNotBefore.remove(eldest)
        }
        retryNotBefore[key] = safeAdd(nowMillis, backoffMillis)
    }

    fun recordSuccess(endpoint: AceLiveTcpPeerEndpoint) = synchronized(lock) {
        retryNotBefore.remove(key(endpoint))
    }

    fun isEligible(
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ): Boolean = synchronized(lock) {
        pruneLocked(nowMillis)
        val retryAt = retryNotBefore[key(endpoint)] ?: return@synchronized true
        nowMillis >= retryAt
    }

    internal fun activeFailureCount(nowMillis: Long = clockMillis()): Int = synchronized(lock) {
        pruneLocked(nowMillis)
        retryNotBefore.size
    }

    private fun key(endpoint: AceLiveTcpPeerEndpoint): EndpointKey =
        EndpointKey(
            host = endpoint.host.lowercase(),
            port = endpoint.port
        )

    private fun pruneLocked(nowMillis: Long) {
        val iterator = retryNotBefore.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis >= entry.value) iterator.remove()
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right <= Long.MAX_VALUE - left) left + right else Long.MAX_VALUE

    private data class EndpointKey(
        val host: String,
        val port: Int
    )

    companion object {
        const val DEFAULT_BACKOFF_MILLIS = 20_000L
        private const val DEFAULT_MAX_ENTRIES = 512
        private const val MAX_ALLOWED_ENTRIES = 2_048
        val shared = AceDhtQueryFailureMemory()
    }
}
