package com.iptv.tv.core.p2p

private const val MAX_ACE_LIVE_REASSEMBLY_PIECE = 0xffff_ffffL
private const val MAX_ACE_LIVE_REASSEMBLY_CHUNKS_PER_PIECE = 0x1_0000

/** A fully assembled Ace Live piece ready for contiguous downstream consumption. */
class AceLiveReassembledPiece(
    val piece: Long,
    val pieceHeader: ByteArray,
    val data: ByteArray
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
 * identity, request scheduling, sockets, discovery, authentication or recovery timing.
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
 * The caller should invoke [appendAcceptedChunk] only when the active-peer coordinator returned an
 * accepted chunk result. Geometry/header checks are intentionally repeated here because this class
 * is also a memory-safety boundary.
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

    fun appendAcceptedChunk(chunk: AceLiveIncomingChunk): AceLiveReassemblyResult {
        if (exhausted) return result(AceLiveReassemblyDisposition.STALE)
        if (chunk.piece !in 0..MAX_ACE_LIVE_REASSEMBLY_PIECE) {
            return result(AceLiveReassemblyDisposition.INVALID_PIECE)
        }
        if (chunk.piece < nextNeeded) {
            return result(AceLiveReassemblyDisposition.STALE)
        }
        if (chunk.piece - nextNeeded >= maxPiecesAhead) {
            return result(AceLiveReassemblyDisposition.TOO_FAR_AHEAD)
        }
        if (chunk.chunkIndex !in 0 until geometry.chunksPerPiece) {
            return result(AceLiveReassemblyDisposition.INVALID_CHUNK_INDEX)
        }

        val expectedPayloadBytes = expectedPayloadBytes(chunk.chunkIndex)
        if (chunk.data.size != expectedPayloadBytes) {
            return result(AceLiveReassemblyDisposition.INVALID_PAYLOAD_SIZE)
        }
        if (AceLivePieceHeaderCodec.decodeUnixSeconds(chunk.pieceHeader) == null) {
            return result(AceLiveReassemblyDisposition.INVALID_HEADER)
        }

        val existing = pieces[chunk.piece]
        if (existing != null && !existing.pieceHeader.contentEquals(chunk.pieceHeader)) {
            return result(AceLiveReassemblyDisposition.PIECE_HEADER_MISMATCH)
        }

        val piece = existing ?: allocatePiece(chunk)
            ?: return result(AceLiveReassemblyDisposition.BUFFER_LIMIT_REACHED)

        if (piece.receivedChunks[chunk.chunkIndex]) {
            return result(AceLiveReassemblyDisposition.DUPLICATE)
        }

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

    fun bufferedPieceCount(): Int = pieces.size

    fun bufferedPayloadBytes(): Long = allocatedPayloadBytes

    fun bufferedPieces(): List<Long> = pieces.keys.toList()

    private fun allocatePiece(chunk: AceLiveIncomingChunk): PieceBuffer? {
        val pieceBytes = geometry.pieceLengthBytes.toLong()
        if (allocatedPayloadBytes > maxBufferedBytes - pieceBytes) return null

        return PieceBuffer(
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
                data = completed.bytes.copyOf()
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
