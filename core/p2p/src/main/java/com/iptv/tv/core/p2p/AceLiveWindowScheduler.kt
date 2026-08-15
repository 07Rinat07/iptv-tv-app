package com.iptv.tv.core.p2p

/**
 * Advertised Ace Live window for one already-handshaked peer.
 *
 * The scheduler treats [peerId] as an opaque local handle. It does not own sockets, peer
 * discovery, authentication or wire requests.
 */
data class AceLivePeerWindow(
    val peerId: Long,
    val minPiece: Long,
    val maxPiece: Long,
    val unchoked: Boolean
) {
    init {
        require(peerId >= 0) { "peerId must be non-negative" }
        require(minPiece >= 0) { "minPiece must be non-negative" }
        require(maxPiece >= minPiece) { "maxPiece must be >= minPiece" }
    }

    fun covers(piece: Long): Boolean = piece in minPiece..maxPiece
}

data class AceLivePieceAssignment(
    val peerId: Long,
    val piece: Long
)

/**
 * Pure Ace Live request scheduler. It models ownership of outstanding piece requests across a
 * small active-peer set while keeping all networking outside this class.
 *
 * Key invariants:
 * - the playback/reassembly `nextNeeded` cursor is authoritative;
 * - a piece is never assigned to two peers at the same time;
 * - only unchoked peers whose advertised window covers the piece may receive new work;
 * - each peer has a bounded number of outstanding pieces;
 * - dropping a peer or moving its window past an outstanding piece requeues that piece;
 * - scheduling is bounded even when a peer advertises an implausibly large live head;
 * - the scheduler never skips an uncovered `nextNeeded` piece on its own. Recovery policy must
 *   explicitly advance the playback cursor before later pieces become schedulable.
 */
class AceLiveWindowScheduler(
    maxInFlightPerPeer: Int
) {
    private val maxInFlight = maxInFlightPerPeer.coerceAtLeast(1)
    private val peers = linkedMapOf<Long, ActivePeer>()
    private val ownerByPiece = sortedMapOf<Long, Long>()

    /**
     * Inserts or refreshes a peer window. Outstanding requests that the refreshed window no longer
     * covers are released immediately so another peer can serve them.
     *
     * @return sorted pieces that became requeueable because of the window change.
     */
    fun updatePeer(window: AceLivePeerWindow): List<Long> {
        val peer = peers[window.peerId]
        if (peer == null) {
            peers[window.peerId] = ActivePeer.from(window)
            return emptyList()
        }

        peer.minPiece = window.minPiece
        peer.maxPiece = window.maxPiece
        peer.unchoked = window.unchoked

        val stale = peer.inFlight
            .filterNot(window::covers)
            .sorted()
        stale.forEach { releaseOwnedPiece(window.peerId, it) }
        return stale
    }

    fun setUnchoked(peerId: Long, unchoked: Boolean) {
        peers[peerId]?.unchoked = unchoked
    }

    /**
     * Removes a peer and makes all of its unfinished assignments available for another peer.
     */
    fun removePeer(peerId: Long): List<Long> {
        val peer = peers.remove(peerId) ?: return emptyList()
        val requeued = peer.inFlight.sorted()
        requeued.forEach { piece ->
            if (ownerByPiece[piece] == peerId) {
                ownerByPiece.remove(piece)
            }
        }
        return requeued
    }

    /**
     * Marks one piece complete. The reassembler owns the bytes from this point onward.
     */
    fun complete(piece: Long) {
        require(piece >= 0) { "piece must be non-negative" }
        val peerId = ownerByPiece.remove(piece) ?: return
        peers[peerId]?.inFlight?.remove(piece)
    }

    /**
     * Releases one timed-out/failed request without removing its peer.
     */
    fun retry(piece: Long) {
        require(piece >= 0) { "piece must be non-negative" }
        val peerId = ownerByPiece.remove(piece) ?: return
        peers[peerId]?.inFlight?.remove(piece)
    }

    /**
     * Clears stale bookkeeping below a cursor that was explicitly advanced by recovery policy.
     */
    fun pruneBefore(nextNeeded: Long) {
        require(nextNeeded >= 0) { "nextNeeded must be non-negative" }
        val stale = ownerByPiece.keys.takeWhile { it < nextNeeded }.toList()
        stale.forEach { piece ->
            val peerId = ownerByPiece.remove(piece)
            if (peerId != null) peers[peerId]?.inFlight?.remove(piece)
        }
    }

    /**
     * Assigns the lowest still-needed pieces toward [head].
     *
     * Work stops at the first unassigned piece that no active unchoked peer currently covers. This
     * deliberately separates normal scheduling from the later "evicted gap" recovery decision.
     */
    fun assign(
        nextNeeded: Long,
        head: Long,
        maxInFlightPerPeer: Int = Int.MAX_VALUE
    ): List<AceLivePieceAssignment> {
        require(nextNeeded >= 0) { "nextNeeded must be non-negative" }
        if (head < nextNeeded || peers.isEmpty()) return emptyList()

        pruneBefore(nextNeeded)

        // Runtime pressure may lower or raise the active depth, but never above the constructor
        // bound. Lowering depth does not cancel existing ownership; it only stops new assignments
        // until each peer naturally falls back under the new limit.
        val effectiveMaxInFlight = maxInFlightPerPeer.coerceIn(1, maxInFlight)
        val spareSlots = peers.values.sumOf { peer ->
            if (peer.unchoked) {
                (effectiveMaxInFlight - peer.inFlight.size).coerceAtLeast(0)
            } else {
                0
            }
        }
        if (spareSlots == 0) return emptyList()

        // The scan budget is bounded by outstanding ownership plus slots that can actually be
        // filled. A hostile/buggy maxPiece cannot make one scheduling tick walk billions of pieces.
        val scanBudget = ownerByPiece.size.toLong() + spareSlots.toLong()
        val scanEnd = minOf(head, saturatingAdd(nextNeeded, scanBudget - 1))

        val out = mutableListOf<AceLivePieceAssignment>()
        var piece = nextNeeded
        while (piece <= scanEnd && out.size < spareSlots) {
            if (ownerByPiece.containsKey(piece)) {
                if (piece == Long.MAX_VALUE) break
                piece += 1
                continue
            }

            val candidate = peers.values
                .asSequence()
                .filter { it.unchoked }
                .filter { it.inFlight.size < effectiveMaxInFlight }
                .filter { piece in it.minPiece..it.maxPiece }
                .minWithOrNull(
                    compareBy<ActivePeer> { it.inFlight.size }
                        .thenByDescending { it.maxPiece }
                        .thenBy { it.peerId }
                )
                ?: break

            candidate.inFlight += piece
            ownerByPiece[piece] = candidate.peerId
            out += AceLivePieceAssignment(candidate.peerId, piece)

            if (piece == Long.MAX_VALUE) break
            piece += 1
        }
        return out
    }

    fun anyUnchokedPeerCovers(piece: Long): Boolean {
        require(piece >= 0) { "piece must be non-negative" }
        return peers.values.any { peer ->
            peer.unchoked && piece in peer.minPiece..peer.maxPiece
        }
    }

    /**
     * Lowest piece that any unchoked peer currently advertises. Recovery may use this only after
     * explicitly deciding that [nextNeeded] has been evicted everywhere.
     */
    fun lowestAvailablePiece(): Long? = peers.values
        .asSequence()
        .filter { it.unchoked }
        .map { it.minPiece }
        .minOrNull()

    fun highestAdvertisedHead(): Long? = peers.values
        .asSequence()
        .filter { it.unchoked }
        .map { it.maxPiece }
        .maxOrNull()

    fun inFlightCount(): Int = ownerByPiece.size

    fun peerInFlightCount(peerId: Long): Int = peers[peerId]?.inFlight?.size ?: 0

    fun ownerOf(piece: Long): Long? = ownerByPiece[piece]

    private fun releaseOwnedPiece(peerId: Long, piece: Long) {
        peers[peerId]?.inFlight?.remove(piece)
        if (ownerByPiece[piece] == peerId) ownerByPiece.remove(piece)
    }

    private fun saturatingAdd(value: Long, delta: Long): Long {
        if (delta <= 0) return value
        return if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
    }

    private data class ActivePeer(
        val peerId: Long,
        var minPiece: Long,
        var maxPiece: Long,
        var unchoked: Boolean,
        val inFlight: MutableSet<Long>
    ) {
        companion object {
            fun from(window: AceLivePeerWindow) = ActivePeer(
                peerId = window.peerId,
                minPiece = window.minPiece,
                maxPiece = window.maxPiece,
                unchoked = window.unchoked,
                inFlight = sortedSetOf()
            )
        }
    }
}
