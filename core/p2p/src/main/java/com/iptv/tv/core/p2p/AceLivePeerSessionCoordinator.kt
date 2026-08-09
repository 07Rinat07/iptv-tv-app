package com.iptv.tv.core.p2p

import kotlin.math.min

private const val MAX_ACE_LIVE_SESSION_PIECE = 0xffff_ffffL

class AceLiveOutboundPeerFrame(
    val request: AceLiveChunkRequest,
    val bytes: ByteArray
)

data class AceLivePeerMessageResult(
    val handled: Boolean,
    val activeChunkDisposition: AceLiveChunkDisposition? = null,
    val reassemblyDisposition: AceLiveReassemblyDisposition? = null,
    val emittedPieces: List<AceLiveReassembledPiece> = emptyList()
)

data class AceLiveRecoveryApplicationResult(
    val emittedPieces: List<AceLiveReassembledPiece>,
    val nextNeededPiece: Long?
)

/**
 * Single-threaded orchestration boundary between verified peer-wire messages, active-peer ownership,
 * recovery and bounded piece reassembly.
 *
 * This class deliberately owns no socket, tracker/DHT discovery, connection retry, handshake
 * signing/authentication or playback muxing. A network adapter feeds decoded messages here and sends
 * only the frames returned by [schedule].
 *
 * Cross-layer invariants enforced here:
 * - reassembly preflight happens before active-peer chunk state mutates;
 * - peer-loss/window/timeout requeue discards partial bytes from the old owner;
 * - reassembler contiguous progress is immediately reflected into active-peer recovery state;
 * - a recovery discontinuity is applied to ownership and reassembly as one serialized operation;
 * - the active scheduling horizon cannot exceed the number of whole piece buffers allowed by the
 *   configured memory budget.
 */
class AceLivePeerSessionCoordinator(
    geometry: AceLiveTransportGeometry,
    initialNextNeededPiece: Long,
    maxInFlightPerPeer: Int,
    recoveryPolicy: AceLiveRecoveryPolicy = AceLiveRecoveryPolicy(),
    requestedMaxAheadPieces: Long = AceLiveActivePeerCoordinator.DEFAULT_MAX_REASSEMBLER_AHEAD_PIECES,
    maxBufferedBytes: Long = AceLivePieceReassembler.DEFAULT_MAX_BUFFERED_BYTES,
    val wireCodec: AceLivePeerWireCodec = AceLivePeerWireCodec()
) {
    val effectiveMaxAheadPieces: Long

    private val activePeers: AceLiveActivePeerCoordinator
    private val reassembler: AceLivePieceReassembler

    init {
        require(requestedMaxAheadPieces > 0) { "requestedMaxAheadPieces must be positive" }
        require(maxBufferedBytes > 0) { "maxBufferedBytes must be positive" }

        val memoryPieceCapacity = maxBufferedBytes / geometry.pieceLengthBytes.toLong()
        require(memoryPieceCapacity > 0) {
            "maxBufferedBytes must fit at least one complete Ace Live piece"
        }
        effectiveMaxAheadPieces = min(requestedMaxAheadPieces, memoryPieceCapacity)

        activePeers = AceLiveActivePeerCoordinator(
            geometry = geometry,
            maxInFlightPerPeer = maxInFlightPerPeer,
            recoveryPolicy = recoveryPolicy,
            maxReassemblerAheadPieces = effectiveMaxAheadPieces
        )
        reassembler = AceLivePieceReassembler(
            geometry = geometry,
            initialNextNeededPiece = initialNextNeededPiece,
            maxPiecesAhead = effectiveMaxAheadPieces,
            maxBufferedBytes = maxBufferedBytes
        )
    }

    fun decodeNext(buffer: ByteArray): AceLivePeerFrameDecodeResult = wireCodec.decodeNext(buffer)

    fun onPeerWindow(window: AceLivePeerWindow): AceLivePeerEventResult {
        val result = activePeers.onPeerEvent(AceLivePeerWindowUpdated(window))
        reassembler.discardPieces(result.requeuedPieces)
        return result
    }

    fun onPeerDropped(peerId: Long): AceLivePeerEventResult {
        val result = activePeers.onPeerEvent(AceLivePeerDropped(peerId))
        reassembler.discardPieces(result.requeuedPieces)
        return result
    }

    fun onPeerMessage(
        peerId: Long,
        message: AceLivePeerWireMessage,
        nowMillis: Long
    ): AceLivePeerMessageResult = when (message) {
        AceLivePeerWireMessage.KeepAlive -> AceLivePeerMessageResult(handled = true)

        AceLivePeerWireMessage.Choke -> {
            activePeers.onPeerEvent(AceLivePeerChokeChanged(peerId = peerId, unchoked = false))
            AceLivePeerMessageResult(handled = true)
        }

        AceLivePeerWireMessage.Unchoke -> {
            activePeers.onPeerEvent(AceLivePeerChokeChanged(peerId = peerId, unchoked = true))
            AceLivePeerMessageResult(handled = true)
        }

        is AceLivePeerWireMessage.LiveChunk -> onLiveChunk(peerId, message, nowMillis)
        is AceLivePeerWireMessage.Unknown -> AceLivePeerMessageResult(handled = false)
    }

    fun schedule(head: Long, nowMillis: Long): List<AceLiveOutboundPeerFrame> {
        val nextNeeded = reassembler.nextNeededPiece() ?: return emptyList()
        return activePeers.schedule(nextNeeded, head, nowMillis).map { request ->
            AceLiveOutboundPeerFrame(
                request = request,
                bytes = wireCodec.encodeChunkRequestFrame(request)
            )
        }
    }

    fun evaluateRecovery(nowMillis: Long): AceLiveRecoveryPlan {
        val nextNeeded = reassembler.nextNeededPiece() ?: return AceLiveRecoveryPlan()
        val plan = activePeers.evaluateRecovery(nextNeeded, nowMillis)
        reassembler.discardPieces(plan.timedOutRequests.map { it.piece })
        return plan
    }

    /** Applies only a decision previously returned by [evaluateRecovery]. */
    fun applyRecoveryAdvance(
        advance: AceLiveCursorAdvance,
        nowMillis: Long
    ): AceLiveRecoveryApplicationResult {
        val current = reassembler.nextNeededPiece()
            ?: error("Ace Live session is exhausted at the u32 piece boundary")
        require(advance.fromPiece == current) { "Cursor advance is stale for reassembler" }
        require(advance.toPiece in 0..MAX_ACE_LIVE_SESSION_PIECE) {
            "Cursor advance must fit Ace Live u32 wire field"
        }

        activePeers.applyCursorAdvance(advance, nowMillis)
        val emitted = reassembler.skipTo(advance.toPiece)
        synchronizeContiguousCursor(nowMillis, expectedAtLeast = advance.toPiece)
        return AceLiveRecoveryApplicationResult(
            emittedPieces = emitted,
            nextNeededPiece = reassembler.nextNeededPiece()
        )
    }

    fun nextNeededPiece(): Long? = reassembler.nextNeededPiece()

    fun bufferedPieceCount(): Int = reassembler.bufferedPieceCount()

    fun bufferedPayloadBytes(): Long = reassembler.bufferedPayloadBytes()

    fun ownerOf(piece: Long): Long? = activePeers.ownerOf(piece)

    private fun onLiveChunk(
        peerId: Long,
        message: AceLivePeerWireMessage.LiveChunk,
        nowMillis: Long
    ): AceLivePeerMessageResult {
        val chunk = AceLiveIncomingChunk(
            peerId = peerId,
            streamIndex = message.streamIndex,
            piece = message.piece,
            chunkIndex = message.chunkIndex,
            pieceHeader = message.pieceHeader,
            data = message.data
        )

        val preflight = reassembler.preflightAcceptedChunk(chunk)
        if (preflight != null && preflight != AceLiveReassemblyDisposition.DUPLICATE) {
            return AceLivePeerMessageResult(
                handled = true,
                reassemblyDisposition = preflight
            )
        }

        val nextNeeded = reassembler.nextNeededPiece()
            ?: return AceLivePeerMessageResult(handled = true)

        if (preflight == AceLiveReassemblyDisposition.DUPLICATE) {
            val owner = activePeers.ownerOf(chunk.piece)
            if (owner == null) {
                return AceLivePeerMessageResult(
                    handled = true,
                    reassemblyDisposition = AceLiveReassemblyDisposition.DUPLICATE
                )
            }

            val activeDuplicate = activePeers.onChunk(chunk = chunk, nextNeeded = nextNeeded)
            return if (activeDuplicate.disposition == AceLiveChunkDisposition.DUPLICATE) {
                AceLivePeerMessageResult(
                    handled = true,
                    activeChunkDisposition = activeDuplicate.disposition,
                    reassemblyDisposition = AceLiveReassemblyDisposition.DUPLICATE
                )
            } else {
                AceLivePeerMessageResult(
                    handled = true,
                    activeChunkDisposition = activeDuplicate.disposition
                )
            }
        }

        val activeResult = activePeers.onChunk(
            chunk = chunk,
            nextNeeded = nextNeeded
        )
        if (!activeResult.accepted) {
            return AceLivePeerMessageResult(
                handled = true,
                activeChunkDisposition = activeResult.disposition
            )
        }

        val reassembly = reassembler.appendAcceptedChunk(chunk)
        check(reassembly.accepted) {
            "Reassembly changed after successful preflight: ${reassembly.disposition}"
        }
        if (reassembly.emittedPieces.isNotEmpty()) {
            synchronizeContiguousCursor(nowMillis)
        }

        return AceLivePeerMessageResult(
            handled = true,
            activeChunkDisposition = activeResult.disposition,
            reassemblyDisposition = reassembly.disposition,
            emittedPieces = reassembly.emittedPieces
        )
    }

    private fun synchronizeContiguousCursor(
        nowMillis: Long,
        expectedAtLeast: Long? = null
    ) {
        val nextNeeded = reassembler.nextNeededPiece() ?: return
        expectedAtLeast?.let { expected ->
            check(nextNeeded >= expected) { "Reassembler cursor regressed" }
        }
        activePeers.onCursorAdvanced(nextNeeded, nowMillis)
    }
}
