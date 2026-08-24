package com.iptv.tv.core.p2p

/**
 * Carries the existing first TCP-connect failure backoff across short-lived Ace Live runtimes.
 *
 * A direct content-id attempt and its direct retry can create separate Runtime/refill-coordinator
 * instances. Without this small shared boundary, the coordinator's normal first-failure backoff is
 * lost at that Runtime boundary and the same dead tracker endpoint can immediately satisfy the next
 * tracker fast path again. Entries are scoped by the exact 20-byte swarm key plus endpoint and live
 * only for the same five-second first-failure backoff already used by [AceLivePeerRefillPolicy].
 * No peer, DHT, request, socket or buffer budget is widened here.
 */
class AceLiveTcpConnectFailureMemory(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val backoffMillis: Long = AceLivePeerRefillPolicy().failureBackoffBaseMillis
) {
    private val lock = Any()
    private val retryNotBefore = LinkedHashMap<EndpointKey, Long>()

    init {
        require(backoffMillis > 0L) { "connect failure backoff must be positive" }
    }

    fun recordFinalPreHandshakeFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ) = synchronized(lock) {
        val key = key(swarmKey, endpoint)
        pruneLocked(nowMillis)
        retryNotBefore.remove(key)
        while (retryNotBefore.size >= MAX_ENTRIES) {
            val eldest = retryNotBefore.keys.firstOrNull() ?: break
            retryNotBefore.remove(eldest)
        }
        retryNotBefore[key] = safeAdd(nowMillis, backoffMillis)
    }

    fun recordConnected(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint
    ) = synchronized(lock) {
        retryNotBefore.remove(key(swarmKey, endpoint))
    }

    fun isEligible(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ): Boolean = synchronized(lock) {
        pruneLocked(nowMillis)
        val retryAt = retryNotBefore[key(swarmKey, endpoint)] ?: return@synchronized true
        nowMillis >= retryAt
    }

    private fun key(swarmKey: ByteArray, endpoint: AceLiveTcpPeerEndpoint): EndpointKey {
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        return EndpointKey(
            swarm = swarmKey.toHexKey(),
            host = endpoint.host,
            port = endpoint.port
        )
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = retryNotBefore.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis >= entry.value) iterator.remove()
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right <= Long.MAX_VALUE - left) left + right else Long.MAX_VALUE

    private fun ByteArray.toHexKey(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class EndpointKey(
        val swarm: String,
        val host: String,
        val port: Int
    )

    companion object {
        private const val MAX_ENTRIES = 512
        val shared = AceLiveTcpConnectFailureMemory()
    }
}
