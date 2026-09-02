package com.iptv.tv.core.p2p

/**
 * Carries direct-runtime pre-handshake peer failures across short-lived Ace Live runtimes.
 *
 * A first final connect-acquisition failure keeps the existing short [backoffMillis] so a transient
 * endpoint can be retried. A post-connect failure before an accepted live handshake is stronger
 * qualification evidence, so [recordFinalPostConnectPreHandshakeFailure] immediately applies the
 * existing [repeatedFailureBackoffMillis] startup-sized backoff. Repeated ordinary acquisition
 * failures still escalate to the same longer backoff while their small failure history is retained.
 * A successful connection/accepted live handshake clears both the active block and retained streak.
 * The retained history is process-local, bounded and does not itself make an endpoint permanently
 * ineligible.
 *
 * Metadata resolution intentionally keeps its existing semantics: it calls [recordConnected] on a
 * physical TCP connection because that is sufficient evidence of metadata-swarm TCP reachability.
 * No peer, DHT, request, socket, startup or buffer budget is widened here.
 */
class AceLiveTcpConnectFailureMemory(
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val backoffMillis: Long = AceLivePeerRefillPolicy().failureBackoffBaseMillis,
    private val repeatedFailureBackoffMillis: Long = DEFAULT_REPEATED_FAILURE_BACKOFF_MILLIS,
    private val failureHistoryRetentionMillis: Long = repeatedFailureBackoffMillis
) {
    private val lock = Any()
    private val failures = LinkedHashMap<EndpointKey, FailureEntry>()

    init {
        require(backoffMillis > 0L) { "connect failure backoff must be positive" }
        require(repeatedFailureBackoffMillis > 0L) {
            "repeated connect failure backoff must be positive"
        }
        require(failureHistoryRetentionMillis >= maxOf(backoffMillis, repeatedFailureBackoffMillis)) {
            "connect failure history must cover every active backoff"
        }
    }

    fun recordFinalPreHandshakeFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ) {
        recordFailure(
            swarmKey = swarmKey,
            endpoint = endpoint,
            nowMillis = nowMillis,
            minimumConsecutiveFailures = 1
        )
    }

    fun recordFinalPostConnectPreHandshakeFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ) {
        recordFailure(
            swarmKey = swarmKey,
            endpoint = endpoint,
            nowMillis = nowMillis,
            minimumConsecutiveFailures = REPEATED_FAILURE_THRESHOLD
        )
    }

    fun recordConnected(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint
    ): Unit = synchronized(lock) {
        failures.remove(key(swarmKey, endpoint))
        Unit
    }

    fun isEligible(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long = clockMillis()
    ): Boolean = synchronized(lock) {
        pruneLocked(nowMillis)
        val failure = failures[key(swarmKey, endpoint)] ?: return@synchronized true
        nowMillis >= failure.retryNotBeforeMillis
    }

    fun activeFailureCount(
        swarmKey: ByteArray,
        nowMillis: Long = clockMillis()
    ): Int = synchronized(lock) {
        val swarm = swarmKeyHex(swarmKey)
        pruneLocked(nowMillis)
        failures.count { (key, failure) ->
            key.swarm == swarm && nowMillis < failure.retryNotBeforeMillis
        }
    }

    fun hasActiveFailure(
        swarmKey: ByteArray,
        nowMillis: Long = clockMillis()
    ): Boolean = activeFailureCount(swarmKey, nowMillis) > 0

    private fun recordFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long,
        minimumConsecutiveFailures: Int
    ) = synchronized(lock) {
        require(minimumConsecutiveFailures in 1..REPEATED_FAILURE_THRESHOLD) {
            "minimum failure count is out of range"
        }
        val key = key(swarmKey, endpoint)
        pruneLocked(nowMillis)
        val previous = failures.remove(key)
        val nextFailureCount = if (previous == null) {
            1
        } else {
            minOf(previous.consecutiveFailures + 1, REPEATED_FAILURE_THRESHOLD)
        }
        val consecutiveFailures = maxOf(minimumConsecutiveFailures, nextFailureCount)
        val currentBackoff = if (consecutiveFailures >= REPEATED_FAILURE_THRESHOLD) {
            repeatedFailureBackoffMillis
        } else {
            backoffMillis
        }
        while (failures.size >= MAX_ENTRIES) {
            val eldest = failures.keys.firstOrNull() ?: break
            failures.remove(eldest)
        }
        failures[key] = FailureEntry(
            retryNotBeforeMillis = safeAdd(nowMillis, currentBackoff),
            consecutiveFailures = consecutiveFailures,
            historyExpiresAtMillis = safeAdd(nowMillis, failureHistoryRetentionMillis)
        )
    }

    private fun key(swarmKey: ByteArray, endpoint: AceLiveTcpPeerEndpoint): EndpointKey =
        EndpointKey(
            swarm = swarmKeyHex(swarmKey),
            host = endpoint.host,
            port = endpoint.port
        )

    private fun swarmKeyHex(swarmKey: ByteArray): String {
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        return swarmKey.toHexKey()
    }

    private fun pruneLocked(nowMillis: Long) {
        val iterator = failures.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMillis >= entry.value.historyExpiresAtMillis) iterator.remove()
        }
    }

    private fun safeAdd(left: Long, right: Long): Long =
        if (right <= Long.MAX_VALUE - left) left + right else Long.MAX_VALUE

    private fun ByteArray.toHexKey(): String = joinToString(separator = "") { byte ->
        (byte.toInt() and 0xff).toString(16).padStart(2, '0')
    }

    private data class FailureEntry(
        val retryNotBeforeMillis: Long,
        val consecutiveFailures: Int,
        val historyExpiresAtMillis: Long
    )

    private data class EndpointKey(
        val swarm: String,
        val host: String,
        val port: Int
    )

    companion object {
        private const val MAX_ENTRIES = 512
        private const val REPEATED_FAILURE_THRESHOLD = 2
        private const val DEFAULT_REPEATED_FAILURE_BACKOFF_MILLIS = 60_000L
        val shared = AceLiveTcpConnectFailureMemory()
    }
}
