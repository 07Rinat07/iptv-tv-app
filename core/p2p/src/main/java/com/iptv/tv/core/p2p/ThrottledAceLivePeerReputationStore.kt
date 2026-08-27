package com.iptv.tv.core.p2p

/**
 * Bounds durable producer evidence without delaying in-memory peer-quality tracking.
 *
 * Media output can be appended many times per second. The file-backed reputation store fsyncs each
 * mutation, so producer evidence must be sampled rather than persisted for every accepted piece.
 * Handshake and final-failure evidence remain immediate and are never throttled here.
 */
internal class ThrottledAceLivePeerReputationStore(
    private val delegate: AceLivePeerReputationStore,
    private val producerEvidenceIntervalMillis: Long = DEFAULT_PRODUCER_EVIDENCE_INTERVAL_MILLIS,
    private val maxTrackedProducerKeys: Int = DEFAULT_MAX_TRACKED_PRODUCER_KEYS
) : AceLivePeerReputationStore {
    private val lock = Any()
    private val lastProducerEvidenceAt = LinkedHashMap<String, Long>()

    init {
        require(producerEvidenceIntervalMillis > 0L) {
            "producerEvidenceIntervalMillis must be positive"
        }
        require(maxTrackedProducerKeys in 1..MAX_ALLOWED_TRACKED_PRODUCER_KEYS) {
            "maxTrackedProducerKeys is out of range"
        }
    }

    override fun snapshot(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ): AceLivePeerReputationSnapshot? = delegate.snapshot(swarmKey, endpoint, nowMillis)

    override fun recordHandshakeAccepted(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) = delegate.recordHandshakeAccepted(swarmKey, endpoint, nowMillis)

    override fun recordMediaProduced(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        val evidenceKey = evidenceKey(swarmKey, endpoint)
        val shouldPersist = synchronized(lock) {
            val previous = lastProducerEvidenceAt[evidenceKey]
            val due = previous == null ||
                nowMillis < previous ||
                nowMillis - previous >= producerEvidenceIntervalMillis
            if (!due) return@synchronized false

            lastProducerEvidenceAt.remove(evidenceKey)
            lastProducerEvidenceAt[evidenceKey] = nowMillis
            while (lastProducerEvidenceAt.size > maxTrackedProducerKeys) {
                val eldest = lastProducerEvidenceAt.keys.firstOrNull() ?: break
                lastProducerEvidenceAt.remove(eldest)
            }
            true
        }
        if (shouldPersist) {
            delegate.recordMediaProduced(swarmKey, endpoint, nowMillis)
        }
    }

    override fun recordFinalFailure(
        swarmKey: ByteArray,
        endpoint: AceLiveTcpPeerEndpoint,
        nowMillis: Long
    ) = delegate.recordFinalFailure(swarmKey, endpoint, nowMillis)

    private fun evidenceKey(swarmKey: ByteArray, endpoint: AceLiveTcpPeerEndpoint): String {
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarmKey must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        val swarm = swarmKey.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xff).toString(16).padStart(2, '0')
        }
        return "$swarm:${endpoint.host.lowercase()}:${endpoint.port}"
    }

    private companion object {
        const val DEFAULT_PRODUCER_EVIDENCE_INTERVAL_MILLIS = 60_000L
        const val DEFAULT_MAX_TRACKED_PRODUCER_KEYS = 1_024
        const val MAX_ALLOWED_TRACKED_PRODUCER_KEYS = 8_192
    }
}
