package com.iptv.tv.core.p2p

/**
 * Runtime-quality view of Ace Live peers.
 *
 * Discovery counts are intentionally kept separate from connected/handshaked/producing peers.
 * A peer is "producing" only after bytes from that peer have contributed to accepted contiguous
 * media output recently enough to still be useful for live playback.
 */
data class AceLivePeerProductionSnapshot(
    val discoveredCandidates: Int,
    val connectedPeers: Int,
    val handshakedPeers: Int,
    val producingPeers: Int,
    val aggregateBytesPerSecond: Long,
    val freshestMediaAgeMillis: Long?
)

internal class AceLivePeerProductionTracker(
    private val producingFreshnessMillis: Long = DEFAULT_PRODUCING_FRESHNESS_MILLIS,
    private val ewmaCurrentWeightPercent: Long = DEFAULT_EWMA_CURRENT_WEIGHT_PERCENT
) {
    private val lock = Any()
    private val peers = linkedMapOf<Long, PeerState>()
    private var discoveredCandidates: Int = 0

    init {
        require(producingFreshnessMillis > 0L) { "producingFreshnessMillis must be positive" }
        require(ewmaCurrentWeightPercent in 1L..100L) {
            "ewmaCurrentWeightPercent must be between 1 and 100"
        }
    }

    fun recordDiscovery(candidateCount: Int): Unit = synchronized(lock) {
        discoveredCandidates = candidateCount.coerceAtLeast(0)
    }

    fun onTransportConnected(peerId: Long, nowMillis: Long): Unit = synchronized(lock) {
        val peer = peers.getOrPut(peerId, ::PeerState)
        peer.connected = true
        peer.handshaked = false
        peer.connectedAtMillis = nowMillis.coerceAtLeast(0L)
    }

    fun onHandshakeAccepted(peerId: Long): Unit = synchronized(lock) {
        val peer = peers.getOrPut(peerId, ::PeerState)
        peer.connected = true
        peer.handshaked = true
    }

    fun onHandshakeRejected(peerId: Long): Unit = synchronized(lock) {
        peers[peerId]?.handshaked = false
    }

    fun onConnectFailed(peerId: Long): Unit = synchronized(lock) {
        peers.getOrPut(peerId, ::PeerState).apply {
            connected = false
            handshaked = false
        }
    }

    fun onDisconnected(peerId: Long): Unit = synchronized(lock) {
        peers[peerId]?.apply {
            connected = false
            handshaked = false
        }
    }

    fun onMediaProduced(peerId: Long, mediaBytes: Long, nowMillis: Long): Unit = synchronized(lock) {
        if (mediaBytes <= 0L) return@synchronized
        val now = nowMillis.coerceAtLeast(0L)
        val peer = peers.getOrPut(peerId, ::PeerState)
        peer.connected = true
        peer.handshaked = true

        val previousAt = peer.lastMediaAtMillis
        if (previousAt != null && now > previousAt) {
            val deltaMillis = now - previousAt
            val instantaneous = safeRate(mediaBytes, deltaMillis)
            peer.ewmaBytesPerSecond = if (peer.ewmaBytesPerSecond <= 0L) {
                instantaneous
            } else {
                weightedAverage(
                    previous = peer.ewmaBytesPerSecond,
                    current = instantaneous,
                    currentWeightPercent = ewmaCurrentWeightPercent
                )
            }
        }
        peer.lastMediaAtMillis = now
        peer.totalMediaBytes = saturatingAdd(peer.totalMediaBytes, mediaBytes)
    }

    fun snapshot(nowMillis: Long): AceLivePeerProductionSnapshot = synchronized(lock) {
        val now = nowMillis.coerceAtLeast(0L)
        val producing = peers.values.filter { peer ->
            peer.connected &&
                peer.handshaked &&
                peer.lastMediaAtMillis?.let { now - it <= producingFreshnessMillis } == true
        }
        AceLivePeerProductionSnapshot(
            discoveredCandidates = discoveredCandidates,
            connectedPeers = peers.values.count { it.connected },
            handshakedPeers = peers.values.count { it.connected && it.handshaked },
            producingPeers = producing.size,
            aggregateBytesPerSecond = producing.fold(0L) { total, peer ->
                saturatingAdd(total, peer.ewmaBytesPerSecond.coerceAtLeast(0L))
            },
            freshestMediaAgeMillis = producing.mapNotNull { peer ->
                peer.lastMediaAtMillis?.let { (now - it).coerceAtLeast(0L) }
            }.minOrNull()
        )
    }

    private fun safeRate(bytes: Long, elapsedMillis: Long): Long {
        if (bytes <= 0L || elapsedMillis <= 0L) return 0L
        return runCatching {
            Math.multiplyExact(bytes, 1_000L) / elapsedMillis
        }.getOrElse { Long.MAX_VALUE }
    }

    private fun weightedAverage(
        previous: Long,
        current: Long,
        currentWeightPercent: Long
    ): Long {
        val previousWeight = 100L - currentWeightPercent
        return runCatching {
            val oldPart = Math.multiplyExact(previous, previousWeight)
            val newPart = Math.multiplyExact(current, currentWeightPercent)
            Math.addExact(oldPart, newPart) / 100L
        }.getOrElse { maxOf(previous, current) }
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        runCatching { Math.addExact(left, right) }.getOrElse { Long.MAX_VALUE }

    private class PeerState {
        var connected: Boolean = false
        var handshaked: Boolean = false
        var connectedAtMillis: Long = 0L
        var lastMediaAtMillis: Long? = null
        var totalMediaBytes: Long = 0L
        var ewmaBytesPerSecond: Long = 0L
    }

    private companion object {
        const val DEFAULT_PRODUCING_FRESHNESS_MILLIS = 10_000L
        const val DEFAULT_EWMA_CURRENT_WEIGHT_PERCENT = 35L
    }
}
