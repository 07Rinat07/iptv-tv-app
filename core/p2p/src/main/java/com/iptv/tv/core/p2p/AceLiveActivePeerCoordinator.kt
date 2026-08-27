package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val ACE_LIVE_STREAM_INDEX = 0L
private const val ACE_LIVE_CHUNK_REQUEST_MESSAGE_ID = 6
private const val MAX_ACE_LIVE_WIRE_PIECE = 0xffff_ffffL
private const val MAX_ACE_LIVE_CHUNK_INDEX = 0xffff
private const val ACE_LIVE_CHUNK_INDEX_CARDINALITY = MAX_ACE_LIVE_CHUNK_INDEX + 1

/**
 * Verified Ace Live request coordinate. The wire payload for message id=6 is exactly
 * `[stream u32=0][piece u32][chunk u16]` in network byte order.
 */
data class AceLiveChunkRequest(
    val peerId: Long,
    val piece: Long,
    val chunkIndex: Int,
    val beginBytes: Int,
    val expectedPayloadBytes: Int
) {
    init {
        require(peerId >= 0) { "peerId must be non-negative" }
        require(piece in 0..MAX_ACE_LIVE_WIRE_PIECE) { "piece must fit Ace Live u32 wire field" }
        require(chunkIndex in 0..MAX_ACE_LIVE_CHUNK_INDEX) {
            "chunkIndex must fit Ace Live u16 wire field"
        }
        require(beginBytes >= 0) { "beginBytes must be non-negative" }
        require(expectedPayloadBytes > 0) { "expectedPayloadBytes must be positive" }
    }

    val messageId: Int
        get() = ACE_LIVE_CHUNK_REQUEST_MESSAGE_ID

    fun wirePayload(): ByteArray = ByteBuffer.allocate(10)
        .order(ByteOrder.BIG_ENDIAN)
        .putInt(ACE_LIVE_STREAM_INDEX.toInt())
        .putInt(piece.toInt())
        .putShort(chunkIndex.toShort())
        .array()
}

/** Events already decoded from a connected Ace Live peer. Networking stays outside core:p2p. */
sealed interface AceLivePeerEvent

data class AceLivePeerWindowUpdated(
    val window: AceLivePeerWindow
) : AceLivePeerEvent

data class AceLivePeerChokeChanged(
    val peerId: Long,
    val unchoked: Boolean
) : AceLivePeerEvent {
    init {
        require(peerId >= 0) { "peerId must be non-negative" }
    }
}

data class AceLivePeerDropped(
    val peerId: Long
) : AceLivePeerEvent {
    init {
        require(peerId >= 0) { "peerId must be non-negative" }
    }
}

data class AceLivePeerEventResult(
    val requeuedPieces: List<Long> = emptyList()
)

/**
 * A live chunk decoded from peer message id=7. The caller keeps ownership of [data]; this
 * coordinator validates request ownership/geometry but deliberately does not buffer media bytes.
 */
class AceLiveIncomingChunk(
    val peerId: Long,
    val streamIndex: Long,
    val piece: Long,
    val chunkIndex: Int,
    val pieceHeader: ByteArray,
    val data: ByteArray
)

enum class AceLiveChunkDisposition {
    ACCEPTED,
    PIECE_COMPLETED,
    DUPLICATE,
    STALE,
    TOO_FAR_AHEAD,
    UNSOLICITED,
    WRONG_PEER,
    WRONG_STREAM,
    INVALID_PIECE,
    INVALID_CHUNK_INDEX,
    INVALID_HEADER,
    PIECE_HEADER_MISMATCH,
    INVALID_PAYLOAD_SIZE
}

data class AceLiveChunkResult(
    val disposition: AceLiveChunkDisposition
) {
    val accepted: Boolean
        get() = disposition == AceLiveChunkDisposition.ACCEPTED ||
            disposition == AceLiveChunkDisposition.PIECE_COMPLETED

    val pieceCompleted: Boolean
        get() = disposition == AceLiveChunkDisposition.PIECE_COMPLETED
}

/**
 * Pure bridge between active-peer events and [AceLiveRecoveryCoordinator].
 *
 * It owns no sockets, tracker/DHT discovery, handshake credentials or media buffers. Its job is to
 * turn piece assignments into the verified Ace Live chunk-request coordinates and reject peer data
 * that was not requested, belongs to another owner, is stale, or is outside the bounded reassembly
 * horizon.
 *
 * A piece remains scheduler-owned until every expected chunk for that piece has arrived exactly
 * once with a consistent verified 8-byte live header. Completing a future piece does not move the
 * authoritative contiguous cursor; the reassembler/player must call [onCursorAdvanced].
 */
class AceLiveActivePeerCoordinator(
    private val geometry: AceLiveTransportGeometry,
    maxInFlightPerPeer: Int,
    private val maxOutstandingChunksPerPiece: Int = DEFAULT_MAX_OUTSTANDING_CHUNKS_PER_PIECE,
    recoveryPolicy: AceLiveRecoveryPolicy = AceLiveRecoveryPolicy(),
    val maxReassemblerAheadPieces: Long = DEFAULT_MAX_REASSEMBLER_AHEAD_PIECES,
    private val chunkRequestRetryMillis: Long = DEFAULT_CHUNK_REQUEST_RETRY_MILLIS
) {
    private val recovery = AceLiveRecoveryCoordinator(
        maxInFlightPerPeer = maxInFlightPerPeer,
        policy = recoveryPolicy
    )
    private val requestedPieces = mutableMapOf<Long, RequestedPiece>()

    init {
        require(maxReassemblerAheadPieces > 0) {
            "maxReassemblerAheadPieces must be positive"
        }
        require(geometry.chunksPerPiece in 1..ACE_LIVE_CHUNK_INDEX_CARDINALITY) {
            "Ace Live chunksPerPiece must fit the u16 chunk index space"
        }
        require(maxOutstandingChunksPerPiece > 0) {
            "maxOutstandingChunksPerPiece must be positive"
        }
        require(chunkRequestRetryMillis > 0L) {
            "chunkRequestRetryMillis must be positive"
        }
    }

    fun onPeerEvent(event: AceLivePeerEvent): AceLivePeerEventResult = when (event) {
        is AceLivePeerWindowUpdated -> {
            requireWireWindow(event.window)
            val requeued = recovery.updatePeer(event.window)
            forgetPieces(requeued)
            AceLivePeerEventResult(requeuedPieces = requeued)
        }

        is AceLivePeerChokeChanged -> {
            recovery.setUnchoked(event.peerId, event.unchoked)
            AceLivePeerEventResult()
        }

        is AceLivePeerDropped -> {
            val requeued = recovery.removePeer(event.peerId)
            forgetPieces(requeued)
            AceLivePeerEventResult(requeuedPieces = requeued)
        }
    }

    /**
     * Assigns piece work and expands each new assignment to all of its verified chunk coordinates.
     * The requested head is additionally capped to the reassembler acceptance horizon.
     */
    fun schedule(
        nextNeeded: Long,
        head: Long,
        nowMillis: Long,
        maxInFlightPerPeer: Int = Int.MAX_VALUE
    ): List<AceLiveChunkRequest> {
        requireWirePiece(nextNeeded)
        if (head < nextNeeded) return emptyList()

        val reassemblyHead = saturatingAdd(nextNeeded, maxReassemblerAheadPieces - 1)
        val safeHead = minOf(head, reassemblyHead, MAX_ACE_LIVE_WIRE_PIECE)
        if (safeHead < nextNeeded) return emptyList()

        recovery.assign(
            nextNeeded = nextNeeded,
            head = safeHead,
            nowMillis = nowMillis,
            maxInFlightPerPeer = maxInFlightPerPeer
        ).forEach { assignment ->
            val state = RequestedPiece(peerId = assignment.peerId)
            check(requestedPieces.put(assignment.piece, state) == null) {
                "Scheduler assigned an already tracked Ace Live piece ${assignment.piece}"
            }
        }

        return requestedPieces.entries.flatMap { (piece, state) ->
            refillChunkRequests(piece = piece, state = state, nowMillis = nowMillis)
        }
    }

    /** Runs the recovery sweep and forgets chunk state for every timed-out piece it requeues. */
    fun evaluateRecovery(nextNeeded: Long, nowMillis: Long): AceLiveRecoveryPlan {
        requireWirePiece(nextNeeded)
        val plan = recovery.evaluate(nextNeeded, nowMillis)
        forgetPieces(plan.timedOutRequests.map { it.piece })
        return plan
    }

    fun applyCursorAdvance(advance: AceLiveCursorAdvance, nowMillis: Long) {
        requireWirePiece(advance.toPiece)
        recovery.applyCursorAdvance(advance, nowMillis)
        forgetBefore(advance.toPiece)
    }

    fun onCursorAdvanced(nextNeeded: Long, nowMillis: Long) {
        requireWirePiece(nextNeeded)
        recovery.onCursorAdvanced(nextNeeded, nowMillis)
        forgetBefore(nextNeeded)
    }

    /**
     * Validates one decoded live chunk against current ownership and geometry.
     * Accepted bytes are not retained here; the session reassembler consumes [AceLiveIncomingChunk]
     * after this method returns an accepted disposition.
     */
    fun onChunk(chunk: AceLiveIncomingChunk, nextNeeded: Long): AceLiveChunkResult {
        requireWirePiece(nextNeeded)

        if (chunk.streamIndex != ACE_LIVE_STREAM_INDEX) {
            return result(AceLiveChunkDisposition.WRONG_STREAM)
        }
        if (chunk.piece !in 0..MAX_ACE_LIVE_WIRE_PIECE) {
            return result(AceLiveChunkDisposition.INVALID_PIECE)
        }
        if (chunk.piece < nextNeeded) {
            return result(AceLiveChunkDisposition.STALE)
        }
        if (isOutsideReassemblyHorizon(chunk.piece, nextNeeded)) {
            return result(AceLiveChunkDisposition.TOO_FAR_AHEAD)
        }

        val state = requestedPieces[chunk.piece]
            ?: return result(AceLiveChunkDisposition.UNSOLICITED)
        if (state.peerId != chunk.peerId || recovery.ownerOf(chunk.piece) != chunk.peerId) {
            return result(AceLiveChunkDisposition.WRONG_PEER)
        }
        if (chunk.chunkIndex !in 0 until geometry.chunksPerPiece) {
            return result(AceLiveChunkDisposition.INVALID_CHUNK_INDEX)
        }
        if (chunk.chunkIndex !in state.requestedChunks) {
            return result(AceLiveChunkDisposition.UNSOLICITED)
        }

        val expectedPayloadBytes = expectedPayloadBytes(chunk.chunkIndex)
        if (chunk.data.size != expectedPayloadBytes) {
            return result(AceLiveChunkDisposition.INVALID_PAYLOAD_SIZE)
        }

        val decodedHeader = AceLivePieceHeaderCodec.decodeUnixSeconds(chunk.pieceHeader)
            ?: return result(AceLiveChunkDisposition.INVALID_HEADER)
        if (!decodedHeader.isFinite()) {
            return result(AceLiveChunkDisposition.INVALID_HEADER)
        }

        val firstHeader = state.pieceHeader
        if (firstHeader == null) {
            state.pieceHeader = chunk.pieceHeader.copyOf()
        } else if (!firstHeader.contentEquals(chunk.pieceHeader)) {
            return result(AceLiveChunkDisposition.PIECE_HEADER_MISMATCH)
        }

        if (!state.receivedChunks.add(chunk.chunkIndex)) {
            return result(AceLiveChunkDisposition.DUPLICATE)
        }
        state.lastRequestedAtMillis.remove(chunk.chunkIndex)

        if (state.receivedChunks.size == geometry.chunksPerPiece) {
            recovery.complete(chunk.piece)
            requestedPieces.remove(chunk.piece)
            return result(AceLiveChunkDisposition.PIECE_COMPLETED)
        }
        return result(AceLiveChunkDisposition.ACCEPTED)
    }

    fun ownerOf(piece: Long): Long? = recovery.ownerOf(piece)

    fun trackedPieceCount(): Int = requestedPieces.size

    private fun refillChunkRequests(
        piece: Long,
        state: RequestedPiece,
        nowMillis: Long
    ): List<AceLiveChunkRequest> {
        val outstanding = state.requestedChunks.size - state.receivedChunks.size

        // A chunk request used to become "spent" forever after one wire send. On a lossy or
        // slow live peer that meant one missing chunk could pin the entire piece until the much
        // coarser piece-level recovery timeout. Retry only still-missing chunks after a short
        // bounded interval; this does not increase outstanding ownership and therefore preserves
        // the existing per-piece backpressure.
        val retryIndices = state.requestedChunks
            .asSequence()
            .filterNot(state.receivedChunks::contains)
            .filter { chunkIndex ->
                val lastRequestedAt = state.lastRequestedAtMillis[chunkIndex] ?: Long.MIN_VALUE
                elapsedSince(lastRequestedAt, nowMillis) >= chunkRequestRetryMillis
            }
            .take(maxOutstandingChunksPerPiece)
            .toList()

        val capacity = (maxOutstandingChunksPerPiece - outstanding).coerceAtLeast(0)
        val newIndices = if (capacity == 0) {
            emptyList()
        } else {
            (0 until geometry.chunksPerPiece)
                .asSequence()
                .filterNot(state.requestedChunks::contains)
                .take(capacity)
                .toList()
        }

        val chunkIndices = (retryIndices + newIndices).distinct()
        if (chunkIndices.isEmpty()) return emptyList()

        state.requestedChunks += newIndices
        chunkIndices.forEach { chunkIndex ->
            state.lastRequestedAtMillis[chunkIndex] = nowMillis
        }

        return chunkIndices.map { chunkIndex ->
            val begin = chunkIndex.toLong() * geometry.chunkLengthBytes.toLong()
            AceLiveChunkRequest(
                peerId = state.peerId,
                piece = piece,
                chunkIndex = chunkIndex,
                beginBytes = begin.toInt(),
                expectedPayloadBytes = expectedPayloadBytes(chunkIndex)
            )
        }
    }

    private fun expectedPayloadBytes(chunkIndex: Int): Int {
        val begin = chunkIndex.toLong() * geometry.chunkLengthBytes.toLong()
        val remaining = geometry.pieceLengthBytes.toLong() - begin
        return minOf(geometry.chunkLengthBytes.toLong(), remaining).toInt()
    }

    private fun isOutsideReassemblyHorizon(piece: Long, nextNeeded: Long): Boolean =
        piece >= saturatingAdd(nextNeeded, maxReassemblerAheadPieces)

    private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
        if (startMillis == Long.MIN_VALUE) {
            Long.MAX_VALUE
        } else {
            (nowMillis - startMillis).coerceAtLeast(0L)
        }

    private fun forgetPieces(pieces: Iterable<Long>) {
        pieces.forEach { piece -> requestedPieces.remove(piece) }
    }

    private fun forgetBefore(nextNeeded: Long) {
        requestedPieces.keys
            .filter { it < nextNeeded }
            .toList()
            .forEach { requestedPieces.remove(it) }
    }

    private fun requireWireWindow(window: AceLivePeerWindow) {
        requireWirePiece(window.minPiece)
        requireWirePiece(window.maxPiece)
    }

    private fun requireWirePiece(piece: Long) {
        require(piece in 0..MAX_ACE_LIVE_WIRE_PIECE) {
            "piece must fit Ace Live u32 wire field"
        }
    }

    private fun saturatingAdd(value: Long, delta: Long): Long {
        if (delta <= 0) return value
        return if (Long.MAX_VALUE - value < delta) Long.MAX_VALUE else value + delta
    }

    private fun result(disposition: AceLiveChunkDisposition) =
        AceLiveChunkResult(disposition = disposition)

    private data class RequestedPiece(
        val peerId: Long,
        var pieceHeader: ByteArray? = null,
        val requestedChunks: MutableSet<Int> = mutableSetOf(),
        val receivedChunks: MutableSet<Int> = mutableSetOf(),
        val lastRequestedAtMillis: MutableMap<Int, Long> = mutableMapOf()
    )

    companion object {
        const val DEFAULT_MAX_REASSEMBLER_AHEAD_PIECES: Long = 512L
        const val DEFAULT_MAX_OUTSTANDING_CHUNKS_PER_PIECE: Int = 24
        const val DEFAULT_CHUNK_REQUEST_RETRY_MILLIS: Long = 1_000L
    }
}
