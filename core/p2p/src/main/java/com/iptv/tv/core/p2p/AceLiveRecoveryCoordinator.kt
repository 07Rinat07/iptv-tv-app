package com.iptv.tv.core.p2p

/**
 * Conservative recovery policy for an Ace Live peer pool.
 *
 * Timing values are local scheduling policy, not transport-descriptor fields. The coordinator
 * never infers timing from the descriptor bitrate and never performs network I/O itself.
 */
data class AceLiveRecoveryPolicy(
    val requestTimeoutMillis: Long = DEFAULT_REQUEST_TIMEOUT_MILLIS,
    val staleUpstreamTimeoutMillis: Long = DEFAULT_STALE_UPSTREAM_TIMEOUT_MILLIS,
    val requestCheckIntervalMillis: Long = DEFAULT_REQUEST_CHECK_INTERVAL_MILLIS,
    val maxPieceAdvance: Long = DEFAULT_MAX_PIECE_ADVANCE
) {
    init {
        require(requestTimeoutMillis > 0) { "requestTimeoutMillis must be positive" }
        require(staleUpstreamTimeoutMillis > requestTimeoutMillis) {
            "staleUpstreamTimeoutMillis must be greater than requestTimeoutMillis"
        }
        require(requestCheckIntervalMillis > 0) { "requestCheckIntervalMillis must be positive" }
        require(requestCheckIntervalMillis <= requestTimeoutMillis) {
            "requestCheckIntervalMillis must not exceed requestTimeoutMillis"
        }
        require(maxPieceAdvance > 0) { "maxPieceAdvance must be positive" }
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MILLIS = 4_000L
        const val DEFAULT_STALE_UPSTREAM_TIMEOUT_MILLIS = 12_000L
        const val DEFAULT_REQUEST_CHECK_INTERVAL_MILLIS = 1_000L
        const val DEFAULT_MAX_PIECE_ADVANCE = 256L
    }
}

data class AceLiveTimedOutRequest(
    val piece: Long,
    val previousPeerId: Long?
)

data class AceLiveCursorAdvance(
    val fromPiece: Long,
    val toPiece: Long
) {
    init {
        require(fromPiece >= 0) { "fromPiece must be non-negative" }
        require(toPiece > fromPiece) { "toPiece must be greater than fromPiece" }
    }

    val skippedPieces: Long
        get() = toPiece - fromPiece
}

data class AceLiveRecoveryPlan(
    val timedOutRequests: List<AceLiveTimedOutRequest> = emptyList(),
    val cursorAdvance: AceLiveCursorAdvance? = null,
    val poolStale: Boolean = false,
    val gapBeyondAdvanceLimit: Boolean = false
) {
    val hasRecoveryWork: Boolean
        get() = timedOutRequests.isNotEmpty() ||
            cursorAdvance != null ||
            poolStale ||
            gapBeyondAdvanceLimit
}

/**
 * Pure state coordinator around [AceLiveWindowScheduler].
 *
 * Responsibilities:
 * - timestamp outstanding piece assignments and requeue timed-out requests;
 * - track the contiguous playback/reassembly cursor rather than arbitrary received pieces;
 * - suggest a discontinuous cursor advance only after the current piece has aged past the request
 *   timeout and no unchoked peer advertises it anymore;
 * - split a large discontinuity into bounded [AceLiveRecoveryPolicy.maxPieceAdvance] steps;
 * - report a stale-but-reachable pool without automatically banning or deleting its peers.
 *
 * The caller must explicitly apply [AceLiveRecoveryPlan.cursorAdvance] through
 * [applyCursorAdvance]. That keeps discontinuity policy visible to the future TS/HLS layer rather
 * than silently skipping inside normal scheduling.
 */
class AceLiveRecoveryCoordinator(
    maxInFlightPerPeer: Int,
    val policy: AceLiveRecoveryPolicy = AceLiveRecoveryPolicy()
) {
    private val scheduler = AceLiveWindowScheduler(maxInFlightPerPeer)
    private val requestStartedAtMillis = mutableMapOf<Long, Long>()

    private var observedNextNeeded: Long? = null
    private var lastProgressAtMillis: Long? = null
    private var lastCheckAtMillis: Long? = null

    fun updatePeer(window: AceLivePeerWindow): List<Long> {
        val requeued = scheduler.updatePeer(window)
        requeued.forEach { piece -> requestStartedAtMillis.remove(piece) }
        return requeued
    }

    fun setUnchoked(peerId: Long, unchoked: Boolean) {
        scheduler.setUnchoked(peerId, unchoked)
    }

    fun removePeer(peerId: Long): List<Long> {
        val requeued = scheduler.removePeer(peerId)
        requeued.forEach { piece -> requestStartedAtMillis.remove(piece) }
        return requeued
    }

    fun assign(
        nextNeeded: Long,
        head: Long,
        nowMillis: Long,
        maxInFlightPerPeer: Int = Int.MAX_VALUE
    ): List<AceLivePieceAssignment> {
        observeCursor(nextNeeded, nowMillis)
        val assignments = scheduler.assign(
            nextNeeded = nextNeeded,
            head = head,
            maxInFlightPerPeer = maxInFlightPerPeer
        )
        assignments.forEach { assignment ->
            requestStartedAtMillis[assignment.piece] = nowMillis
        }
        return assignments
    }

    /** Marks network/reassembly ownership complete without claiming contiguous playback progress. */
    fun complete(piece: Long) {
        require(piece >= 0) { "piece must be non-negative" }
        scheduler.complete(piece)
        requestStartedAtMillis.remove(piece)
    }

    /**
     * Records authoritative contiguous progress from the reassembler/player cursor.
     * Receiving a future piece alone must not reset the stall timer.
     */
    fun onCursorAdvanced(nextNeeded: Long, nowMillis: Long) {
        validateClock(nowMillis)
        require(nextNeeded >= 0) { "nextNeeded must be non-negative" }

        val previous = observedNextNeeded
        if (previous == null || nextNeeded != previous) {
            observedNextNeeded = nextNeeded
            lastProgressAtMillis = nowMillis
        }

        scheduler.pruneBefore(nextNeeded)
        requestStartedAtMillis.keys
            .filter { it < nextNeeded }
            .toList()
            .forEach { piece -> requestStartedAtMillis.remove(piece) }
    }

    /**
     * Applies a previously surfaced discontinuity decision. The coordinator verifies that the
     * decision still starts at the currently observed cursor and stays within the configured cap.
     */
    fun applyCursorAdvance(advance: AceLiveCursorAdvance, nowMillis: Long) {
        validateClock(nowMillis)
        val current = observedNextNeeded
            ?: error("Cannot apply Ace Live cursor advance before observing a cursor")
        require(advance.fromPiece == current) { "Cursor advance is stale" }
        require(advance.skippedPieces <= policy.maxPieceAdvance) {
            "Cursor advance exceeds maxPieceAdvance"
        }
        onCursorAdvanced(advance.toPiece, nowMillis)
    }

    /**
     * Runs one bounded recovery sweep. Calls made faster than the configured check interval still
     * observe cursor progress but do not repeat timeout/recovery work.
     */
    fun evaluate(nextNeeded: Long, nowMillis: Long): AceLiveRecoveryPlan {
        observeCursor(nextNeeded, nowMillis)

        val lastCheck = lastCheckAtMillis
        if (
            lastCheck != null &&
            elapsedSince(lastCheck, nowMillis) < policy.requestCheckIntervalMillis
        ) {
            return AceLiveRecoveryPlan()
        }
        lastCheckAtMillis = nowMillis

        val timedOutPieces = requestStartedAtMillis.entries
            .filter { (piece, startedAt) ->
                piece >= nextNeeded && elapsedSince(startedAt, nowMillis) >= policy.requestTimeoutMillis
            }
            .map { it.key }
            .sorted()

        val timedOut = timedOutPieces.map { piece ->
            val previousPeer = scheduler.ownerOf(piece)
            scheduler.retry(piece)
            requestStartedAtMillis.remove(piece)
            AceLiveTimedOutRequest(piece = piece, previousPeerId = previousPeer)
        }

        val stalledFor = elapsedSince(lastProgressAtMillis ?: nowMillis, nowMillis)
        var cursorAdvance: AceLiveCursorAdvance? = null
        var beyondLimit = false

        if (
            stalledFor >= policy.requestTimeoutMillis &&
            !scheduler.anyUnchokedPeerCovers(nextNeeded)
        ) {
            val lowestAvailable = scheduler.lowestAvailablePiece()
            if (lowestAvailable != null && lowestAvailable > nextNeeded) {
                val distance = lowestAvailable - nextNeeded
                val boundedDistance = minOf(distance, policy.maxPieceAdvance)
                cursorAdvance = AceLiveCursorAdvance(
                    fromPiece = nextNeeded,
                    toPiece = nextNeeded + boundedDistance
                )
                beyondLimit = distance > policy.maxPieceAdvance
            }
        }

        val poolStale = stalledFor >= policy.staleUpstreamTimeoutMillis &&
            scheduler.highestAdvertisedHead() != null

        return AceLiveRecoveryPlan(
            timedOutRequests = timedOut,
            cursorAdvance = cursorAdvance,
            poolStale = poolStale,
            gapBeyondAdvanceLimit = beyondLimit
        )
    }

    fun ownerOf(piece: Long): Long? = scheduler.ownerOf(piece)

    fun inFlightCount(): Int = scheduler.inFlightCount()

    fun lowestAvailablePiece(): Long? = scheduler.lowestAvailablePiece()

    fun highestAdvertisedHead(): Long? = scheduler.highestAdvertisedHead()

    private fun observeCursor(nextNeeded: Long, nowMillis: Long) {
        validateClock(nowMillis)
        require(nextNeeded >= 0) { "nextNeeded must be non-negative" }

        val previous = observedNextNeeded
        when {
            previous == null -> {
                observedNextNeeded = nextNeeded
                lastProgressAtMillis = nowMillis
            }

            nextNeeded != previous -> onCursorAdvanced(nextNeeded, nowMillis)
        }
    }

    private fun validateClock(nowMillis: Long) {
        require(nowMillis >= 0) { "nowMillis must be non-negative" }
    }

    private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
        (nowMillis - startMillis).coerceAtLeast(0L)
}
