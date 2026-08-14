package com.iptv.tv.core.p2p

/**
 * Runtime-quality view of Ace Live peers.
 *
 * Discovery counts are intentionally kept separate from connected/handshaked/requestable/producing
 * peers. A peer is "producing" only while its advertised live window is useful to the authoritative
 * cursor, it is currently unchoked, and its recent bytes contributed to accepted contiguous media.
 */
data class AceLivePeerProductionSnapshot(
    val discoveredCandidates: Int,
    val connectedPeers: Int,
    val handshakedPeers: Int,
    val windowUsefulPeers: Int,
    val unchokedPeers: Int,
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
        peer.windowUseful = false
        peer.unchoked = false
        peer.connectedAtMillis = nowMillis.coerceAtLeast(0L)
    }

    fun onHandshakeAccepted(peerId: Long): Unit = synchronized(lock) {
        val peer = peers.getOrPut(peerId, ::PeerState)
        peer.connected = true
        peer.handshaked = true
    }

    fun onHandshakeRejected(peerId: Long): Unit = synchronized(lock) {
        peers[peerId]?.apply {
            handshaked = false
            windowUseful = false
            unchoked = false
        }
    }

    fun onConnectFailed(peerId: Long): Unit = synchronized(lock) {
        peers.getOrPut(peerId, ::PeerState).apply {
            connected = false
            handshaked = false
            windowUseful = false
            unchoked = false
        }
    }

    fun onDisconnected(peerId: Long): Unit = synchronized(lock) {
        peers[peerId]?.apply {
            connected = false
            handshaked = false
            windowUseful = false
            unchoked = false
        }
    }

    /**
     * Updates the two requestability stages derived from verified peer state.
     *
     * [windowUseful] means the peer's currently advertised live window contains the authoritative
     * next-needed piece. [unchoked] means the peer currently allows requests. Neither flag is inferred
     * from discovery or past media delivery.
     */
    fun onPeerRequestability(
        peerId: Long,
        windowUseful: Boolean,
        unchoked: Boolean
    ): Unit = synchronized(lock) {
        val peer = peers.getOrPut(peerId, ::PeerState)
        peer.windowUseful = peer.connected && peer.handshaked && windowUseful
        peer.unchoked = peer.connected && peer.handshaked && unchoked
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
        val connected = peers.values.filter { it.connected }
        val handshaked = connected.filter { it.handshaked }
        val windowUseful = handshaked.filter { it.windowUseful }
        val unchoked = handshaked.filter { it.unchoked }
        val producing = handshaked.filter { peer ->
            peer.windowUseful &&
                peer.unchoked &&
                peer.lastMediaAtMillis?.let { now - it <= producingFreshnessMillis } == true
        }
        AceLivePeerProductionSnapshot(
            discoveredCandidates = discoveredCandidates,
            connectedPeers = connected.size,
            handshakedPeers = handshaked.size,
            windowUsefulPeers = windowUseful.size,
            unchokedPeers = unchoked.size,
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
        var windowUseful: Boolean = false
        var unchoked: Boolean = false
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