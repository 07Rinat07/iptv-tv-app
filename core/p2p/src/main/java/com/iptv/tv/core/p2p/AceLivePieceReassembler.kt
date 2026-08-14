package com.iptv.tv.core.p2p

private const val MAX_ACE_LIVE_REASSEMBLY_PIECE = 0xffff_ffffL
private const val MAX_ACE_LIVE_REASSEMBLY_CHUNKS_PER_PIECE = 0x1_0000

/** A fully assembled Ace Live piece ready for contiguous downstream consumption. */
class AceLiveReassembledPiece(
    val piece: Long,
    val pieceHeader: ByteArray,
    val data: ByteArray,
    val sourcePeerId: Long
)

enum class AceLiveReassemblyDisposition {
    ACCEPTED,
    DUPLICATE,
    STALE,
    TOO_FAR_AHEAD,
    BUFFER_LIMIT_REACHED,
    INVALID_PIECE,
    INVALID_CHUNK_INDEX,
    INVALID_HEADER,
    PIECE_HEADER_MISMATCH,
    INVALID_PAYLOAD_SIZE
}

data class AceLiveReassemblyResult(
    val disposition: AceLiveReassemblyDisposition,
    val emittedPieces: List<AceLiveReassembledPiece> = emptyList(),
    val nextNeededPiece: Long? = null
) {
    val accepted: Boolean
        get() = disposition == AceLiveReassemblyDisposition.ACCEPTED
}

/**
 * Pure bounded reassembler for chunks that already passed [AceLiveActivePeerCoordinator].
 *
 * This class owns payload assembly and contiguous emission only. It deliberately does not own peer
 * identity decisions, request scheduling, sockets, discovery, authentication or recovery timing;
 * it only preserves the source-peer provenance already verified by the ownership layer.
 *
 * Key invariants:
 * - [nextNeededPiece] is the only emission cursor;
 * - a completed future piece stays buffered until every earlier piece is emitted or [skipTo] is
 *   called explicitly by recovery policy;
 * - stale and far-future chunks are rejected before a new piece buffer is allocated;
 * - total allocated piece payload memory is capped by [maxBufferedBytes];
 * - every chunk must match verified geometry and all chunks of a piece must carry one identical
 *   valid 8-byte live header;
 * - duplicate chunks are idempotent and never count twice;
 * - piece numbers never wrap implicitly at the u32 wire boundary.
 *
 * [preflightAcceptedChunk] is a non-mutating guard for the session orchestration layer. It lets the
 * caller reject memory/header/geometry problems before mutating active-peer ownership state.
 * [discardPieces] is the matching conservative boundary for peer-loss/timeout requeue: partial
 * bytes from an old owner are dropped before another peer owns the same piece.
 *
 * This class is intentionally single-threaded. The session coordinator serializes peer events.
 */
class AceLivePieceReassembler(
    private val geometry: AceLiveTransportGeometry,
    initialNextNeededPiece: Long,
    val maxPiecesAhead: Long = AceLiveActivePeerCoordinator.DEFAULT_MAX_REASSEMBLER_AHEAD_PIECES,
    val maxBufferedBytes: Long = DEFAULT_MAX_BUFFERED_BYTES
) {
    private val pieces = sortedMapOf<Long, PieceBuffer>()
    private var nextNeeded = initialNextNeededPiece
    private var exhausted = false
    private var allocatedPayloadBytes = 0L

    init {
        require(initialNextNeededPiece in 0..MAX_ACE_LIVE_REASSEMBLY_PIECE) {
            "initialNextNeededPiece must fit Ace Live u32 wire field"
        }
        require(maxPiecesAhead > 0) { "maxPiecesAhead must be positive" }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }
        require(geometry.chunksPerPiece in 1..MAX_ACE_LIVE_REASSEMBLY_CHUNKS_PER_PIECE) {
            "Ace Live chunksPerPiece must fit the u16 chunk index space"
        }
        require(geometry.pieceLengthBytes.toLong() <= maxBufferedBytes) {
            "maxBufferedBytes must fit at least one complete Ace Live piece"
        }
    }

    /** Returns null when [appendAcceptedChunk] can mutate safely without changing state here. */
    fun preflightAcceptedChunk(chunk: AceLiveIncomingChunk): AceLiveReassemblyDisposition? {
        if (exhausted) return AceLiveReassemblyDisposition.STALE
        if (chunk.piece !in 0..MAX_ACE_LIVE_REASSEMBLY_PIECE) {
            return AceLiveReassemblyDisposition.INVALID_PIECE
        }
        if (chunk.piece < nextNeeded) {
            return AceLiveReassemblyDisposition.STALE
        }
        if (chunk.piece - nextNeeded >= maxPiecesAhead) {
            return AceLiveReassemblyDisposition.TOO_FAR_AHEAD
        }
        if (chunk.chunkIndex !in 0 until geometry.chunksPerPiece) {
            return AceLiveReassemblyDisposition.INVALID_CHUNK_INDEX
        }

        if (chunk.data.size != expectedPayloadBytes(chunk.chunkIndex)) {
            return AceLiveReassemblyDisposition.INVALID_PAYLOAD_SIZE
        }
        if (AceLivePieceHeaderCodec.decodeUnixSeconds(chunk.pieceHeader) == null) {
            return AceLiveReassemblyDisposition.INVALID_HEADER
        }

        val existing = pieces[chunk.piece]
        if (existing != null) {
            if (!existing.pieceHeader.contentEquals(chunk.pieceHeader)) {
                return AceLiveReassemblyDisposition.PIECE_HEADER_MISMATCH
            }
            if (existing.receivedChunks[chunk.chunkIndex]) {
                return AceLiveReassemblyDisposition.DUPLICATE
            }
            return null
        }

        val pieceBytes = geometry.pieceLengthBytes.toLong()
        if (allocatedPayloadBytes > maxBufferedBytes - pieceBytes) {
            return AceLiveReassemblyDisposition.BUFFER_LIMIT_REACHED
        }
        return null
    }

    fun appendAcceptedChunk(chunk: AceLiveIncomingChunk): AceLiveReassemblyResult {
        preflightAcceptedChunk(chunk)?.let { disposition ->
            return result(disposition)
        }

        val piece = pieces[chunk.piece] ?: allocatePiece(chunk)
        val begin = chunk.chunkIndex.toLong() * geometry.chunkLengthBytes.toLong()
        chunk.data.copyInto(
            destination = piece.bytes,
            destinationOffset = begin.toInt()
        )
        piece.receivedChunks[chunk.chunkIndex] = true
        piece.receivedCount += 1
        if (piece.receivedCount == geometry.chunksPerPiece) {
            piece.complete = true
        }

        return result(
            disposition = AceLiveReassemblyDisposition.ACCEPTED,
            emittedPieces = drainContiguous()
        )
    }

    /**
     * Drops selected buffered pieces without moving the authoritative cursor. Used when active-peer
     * ownership is requeued after peer loss, a window shift or timeout.
     */
    fun discardPieces(pieceNumbers: Iterable<Long>): List<Long> {
        val discarded = mutableListOf<Long>()
        pieceNumbers.distinct().forEach { piece ->
            if (removePiece(piece) != null) discarded += piece
        }
        return discarded
    }

    /**
     * Explicit discontinuity boundary used only after recovery has approved a cursor advance.
     * Buffered state below [newNextNeededPiece] is discarded. If the destination (and subsequent
     * pieces) were already complete, they are emitted immediately in order.
     */
    fun skipTo(newNextNeededPiece: Long): List<AceLiveReassembledPiece> {
        check(!exhausted) { "Ace Live reassembler is exhausted at the u32 piece boundary" }
        require(newNextNeededPiece in 0..MAX_ACE_LIVE_REASSEMBLY_PIECE) {
            "newNextNeededPiece must fit Ace Live u32 wire field"
        }
        require(newNextNeededPiece > nextNeeded) {
            "newNextNeededPiece must advance the contiguous cursor"
        }

        pieces.keys
            .takeWhile { it < newNextNeededPiece }
            .toList()
            .forEach(::removePiece)
        nextNeeded = newNextNeededPiece
        return drainContiguous()
    }

    fun nextNeededPiece(): Long? = nextNeeded.takeUnless { exhausted }

    /** Sets the first live cursor once, before any request or payload has been accepted. */
    fun initializeAt(piece: Long) {
        require(piece in 0..MAX_ACE_LIVE_REASSEMBLY_PIECE) {
            "initial live piece must fit Ace Live u32 wire field"
        }
        check(pieces.isEmpty() && allocatedPayloadBytes == 0L) {
            "Ace Live reassembler already owns buffered data"
        }
        check(!exhausted) { "Ace Live reassembler is exhausted" }
        nextNeeded = piece
    }

    fun bufferedPieceCount(): Int = pieces.size

    fun bufferedPayloadBytes(): Long = allocatedPayloadBytes

    fun bufferedPieces(): List<Long> = pieces.keys.toList()

    private fun allocatePiece(chunk: AceLiveIncomingChunk): PieceBuffer {
        val pieceBytes = geometry.pieceLengthBytes.toLong()
        check(allocatedPayloadBytes <= maxBufferedBytes - pieceBytes) {
            "Ace Live reassembler allocation must be preflighted"
        }

        return PieceBuffer(
            sourcePeerId = chunk.peerId,
            pieceHeader = chunk.pieceHeader.copyOf(),
            bytes = ByteArray(geometry.pieceLengthBytes),
            receivedChunks = BooleanArray(geometry.chunksPerPiece)
        ).also { piece ->
            pieces[chunk.piece] = piece
            allocatedPayloadBytes += pieceBytes
        }
    }

    private fun drainContiguous(): List<AceLiveReassembledPiece> {
        val emitted = mutableListOf<AceLiveReassembledPiece>()
        while (!exhausted) {
            val piece = pieces[nextNeeded] ?: break
            if (!piece.complete) break

            val emittedPieceNumber = nextNeeded
            val completed = removePiece(emittedPieceNumber) ?: break
            emitted += AceLiveReassembledPiece(
                piece = emittedPieceNumber,
                pieceHeader = completed.pieceHeader.copyOf(),
                data = completed.bytes.copyOf(),
                sourcePeerId = completed.sourcePeerId
            )

            if (nextNeeded == MAX_ACE_LIVE_REASSEMBLY_PIECE) {
                exhausted = true
            } else {
                nextNeeded += 1
            }
        }
        return emitted
    }

    private fun removePiece(piece: Long): PieceBuffer? {
        val removed = pieces.remove(piece) ?: return null
        allocatedPayloadBytes -= geometry.pieceLengthBytes.toLong()
        check(allocatedPayloadBytes >= 0) { "Ace Live reassembler byte accounting underflow" }
        return removed
    }

    private fun expectedPayloadBytes(chunkIndex: Int): Int {
        val begin = chunkIndex.toLong() * geometry.chunkLengthBytes.toLong()
        val remaining = geometry.pieceLengthBytes.toLong() - begin
        return minOf(geometry.chunkLengthBytes.toLong(), remaining).toInt()
    }

    private fun result(
        disposition: AceLiveReassemblyDisposition,
        emittedPieces: List<AceLiveReassembledPiece> = emptyList()
    ) = AceLiveReassemblyResult(
        disposition = disposition,
        emittedPieces = emittedPieces,
        nextNeededPiece = nextNeededPiece()
    )

    private data class PieceBuffer(
        val sourcePeerId: Long,
        val pieceHeader: ByteArray,
        val bytes: ByteArray,
        val receivedChunks: BooleanArray,
        var receivedCount: Int = 0,
        var complete: Boolean = false
    )

    companion object {
        const val DEFAULT_MAX_BUFFERED_BYTES: Long = 32L * 1024L * 1024L
    }
}
