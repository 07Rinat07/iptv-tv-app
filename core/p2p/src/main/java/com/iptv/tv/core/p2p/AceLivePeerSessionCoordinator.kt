package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicLong
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

/**
 * Explicit boundary event emitted only when recovery intentionally skips an evicted live gap.
 *
 * The P2P layer does not decide how MPEG-TS/HLS should re-gate after the jump. A future playback
 * adapter can consume this event to flush decoder state, wait for a keyframe, or start a new HLS
 * discontinuity sequence without trying to infer a jump from non-contiguous piece numbers.
 */
data class AceLiveOutputDiscontinuity(
    val fromPiece: Long,
    val toPiece: Long,
    val reason: AceLiveOutputDiscontinuityReason
) {
    init {
        require(fromPiece >= 0) { "fromPiece must be non-negative" }
        require(toPiece > fromPiece) { "toPiece must be greater than fromPiece" }
    }

    val skippedPieces: Long
        get() = toPiece - fromPiece
}

enum class AceLiveOutputDiscontinuityReason {
    RECOVERY_EVICTED_GAP
}

data class AceLiveRecoveryApplicationResult(
    val emittedPieces: List<AceLiveReassembledPiece>,
    val nextNeededPiece: Long?,
    val outputDiscontinuity: AceLiveOutputDiscontinuity? = null
)

/**
 * Single-threaded orchestration boundary between verified peer-wire messages, active-peer ownership,
 * recovery and bounded piece reassembly.
 *
 * This class deliberately owns no socket, tracker/DHT discovery, connection retry, handshake
 * signing/authentication or playback muxing. The network adapter feeds decoded messages here and sends
 * only the frames returned by [schedule].
 *
 * Cross-layer invariants enforced here:
 * - reassembly preflight happens before active-peer chunk state mutates;
 * - peer-loss/window/timeout requeue discards partial bytes from the old owner;
 * - reassembler contiguous progress is immediately reflected into active-peer recovery state;
 * - a recovery discontinuity is applied to ownership and reassembly as one serialized operation;
 * - an applied recovery discontinuity is surfaced explicitly to the media-output boundary;
 * - the active scheduling horizon cannot exceed the number of whole piece buffers allowed by the
 *   configured memory budget.
 */
class AceLivePeerSessionCoordinator(
    geometry: AceLiveTransportGeometry,
    initialNextNeededPiece: Long,
    maxInFlightPerPeer: Int,
    maxOutstandingChunksPerPiece: Int = AceLiveActivePeerCoordinator.DEFAULT_MAX_OUTSTANDING_CHUNKS_PER_PIECE,
    recoveryPolicy: AceLiveRecoveryPolicy = AceLiveRecoveryPolicy(),
    requestedMaxAheadPieces: Long = AceLiveActivePeerCoordinator.DEFAULT_MAX_REASSEMBLER_AHEAD_PIECES,
    maxBufferedBytes: Long = AceLivePieceReassembler.DEFAULT_MAX_BUFFERED_BYTES,
    val wireCodec: AceLivePeerWireCodec = AceLivePeerWireCodec(),
    private val producerBoundaryDiagnostics: AceLiveProducerBoundaryDiagnosticsReporter =
        AceLiveProducerBoundaryDiagnosticsReporter()
) {
    val effectiveMaxAheadPieces: Long

    private val activePeers: AceLiveActivePeerCoordinator
    private val reassembler: AceLivePieceReassembler
    private val producerBoundarySessionId = nextProducerBoundarySessionId.getAndIncrement()
    private var initializedFromLiveWindow: Boolean = false
    private var emittedAnyPiece: Boolean = false

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
            maxOutstandingChunksPerPiece = maxOutstandingChunksPerPiece,
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

        is AceLivePeerWireMessage.Have -> AceLivePeerMessageResult(handled = false)
        is AceLivePeerWireMessage.StreamHave -> AceLivePeerMessageResult(handled = false)
        is AceLivePeerWireMessage.LiveStatus -> AceLivePeerMessageResult(handled = false)
        is AceLivePeerWireMessage.LiveChunk -> onLiveChunk(peerId, message, nowMillis)
        is AceLivePeerWireMessage.Unknown -> AceLivePeerMessageResult(handled = false)
    }

    fun schedule(
        head: Long,
        nowMillis: Long,
        maxInFlightPerPeer: Int = Int.MAX_VALUE
    ): List<AceLiveOutboundPeerFrame> {
        val nextNeeded = reassembler.nextNeededPiece() ?: return emptyList()
        val scheduled = activePeers.schedule(
            nextNeeded = nextNeeded,
            head = head,
            nowMillis = nowMillis,
            maxInFlightPerPeer = maxInFlightPerPeer
        )
        scheduled.forEach { request ->
            producerBoundaryDiagnostics.record(
                sessionId = producerBoundarySessionId,
                stage = AceLiveProducerBoundaryStage.SCHEDULED,
                peerId = request.peerId,
                piece = request.piece,
                nowMillis = nowMillis
            )
        }
        return scheduled.map { request ->
            AceLiveOutboundPeerFrame(
                request = request,
                bytes = wireCodec.encodeChunkRequestFrame(request)
            )
        }
    }

    internal fun reportRequestSelected(
        peerId: Long,
        piece: Long,
        nowMillis: Long
    ) {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = AceLiveProducerBoundaryStage.SELECTED,
            peerId = peerId,
            piece = piece,
            nowMillis = nowMillis
        )
    }

    internal fun reportRequestSent(
        peerId: Long,
        piece: Long,
        nowMillis: Long
    ) = reportBoundary(
        stage = AceLiveProducerBoundaryStage.SENT,
        peerId = peerId,
        piece = piece,
        nowMillis = nowMillis
    )

    internal fun reportPieceAuthenticated(
        peerId: Long,
        piece: Long,
        bytes: Long,
        nowMillis: Long
    ) = reportBoundary(
        stage = AceLiveProducerBoundaryStage.AUTHENTICATED,
        peerId = peerId,
        piece = piece,
        bytes = bytes,
        nowMillis = nowMillis
    )

    internal fun reportAuthenticationRejected(
        peerId: Long,
        piece: Long,
        disposition: String,
        nowMillis: Long
    ) = reportBoundary(
        stage = AceLiveProducerBoundaryStage.AUTHENTICATION_REJECTED,
        peerId = peerId,
        piece = piece,
        disposition = disposition,
        nowMillis = nowMillis
    )

    internal fun reportTsResyncOutput(
        peerId: Long,
        piece: Long,
        bytes: Long,
        nowMillis: Long
    ) = reportBoundary(
        stage = AceLiveProducerBoundaryStage.TS_RESYNC_OUTPUT,
        peerId = peerId,
        piece = piece,
        bytes = bytes,
        nowMillis = nowMillis
    )

    internal fun reportMediaAppended(
        peerId: Long,
        piece: Long,
        bytes: Long,
        nowMillis: Long
    ) = reportBoundary(
        stage = AceLiveProducerBoundaryStage.MEDIA_APPENDED,
        peerId = peerId,
        piece = piece,
        bytes = bytes,
        nowMillis = nowMillis
    )

    fun evaluateRecovery(nowMillis: Long): AceLiveRecoveryPlan {
        val nextNeeded = reassembler.nextNeededPiece() ?: return AceLiveRecoveryPlan()
        val plan = activePeers.evaluateRecovery(nextNeeded, nowMillis)
        reassembler.discardPieces(plan.timedOutRequests.map { it.piece })
        plan.timedOutRequests.forEach { timedOut ->
            reportBoundary(
                stage = AceLiveProducerBoundaryStage.REQUEST_TIMEOUT,
                peerId = timedOut.previousPeerId,
                piece = timedOut.piece,
                acceptedChunks = timedOut.acceptedChunks,
                assignmentAgeMillis = timedOut.assignmentAgeMillis,
                progressAgeMillis = timedOut.progressAgeMillis,
                nowMillis = nowMillis
            )
        }
        return plan
    }

    /**
     * Applies only a decision previously returned by [evaluateRecovery]. The returned
     * [AceLiveRecoveryApplicationResult.outputDiscontinuity] is the sole explicit signal that the
     * output cursor jumped; normal contiguous piece emission never synthesizes one.
     */
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
        emitted.forEach { piece ->
            reportPieceCompleted(piece, nowMillis)
        }
        return AceLiveRecoveryApplicationResult(
            emittedPieces = emitted,
            nextNeededPiece = reassembler.nextNeededPiece(),
            outputDiscontinuity = AceLiveOutputDiscontinuity(
                fromPiece = advance.fromPiece,
                toPiece = advance.toPiece,
                reason = AceLiveOutputDiscontinuityReason.RECOVERY_EVICTED_GAP
            )
        )
    }

    fun nextNeededPiece(): Long? = reassembler.nextNeededPiece()

    /**
     * Selects the initial cursor from the first verified peer window. During startup, a later
     * verified window may move that cursor forward only when the old cursor has definitely fallen
     * below the advertised live floor and no payload is buffered or has been emitted yet. This
     * prevents a stale first peer window from pinning startup to already-evicted pieces while keeping
     * established output and ordinary reconnects monotonic.
     */
    fun initializeFromLiveWindow(
        window: AceLivePeerAdvertisedWindow,
        prefetchPieces: Long,
        nowMillis: Long
    ): Long {
        require(prefetchPieces >= 0) { "prefetchPieces must be non-negative" }
        val head = (window.position ?: window.maxPiece).coerceIn(window.minPiece, window.maxPiece)
        val start = head.saturatingSubtract(prefetchPieces).coerceAtLeast(window.minPiece)

        if (initializedFromLiveWindow) {
            val current = reassembler.nextNeededPiece()
                ?: error("Ace Live session is exhausted at the u32 piece boundary")
            val canRebaseStartup = current < window.minPiece &&
                !emittedAnyPiece &&
                reassembler.bufferedPieceCount() == 0
            if (!canRebaseStartup) return current

            val emitted = reassembler.skipTo(start)
            check(emitted.isEmpty()) { "Ace Live startup rebase unexpectedly emitted buffered pieces" }
            activePeers.onCursorAdvanced(start, nowMillis)
            return start
        }

        check(activePeers.trackedPieceCount() == 0) {
            "Ace Live initial cursor cannot change after scheduling starts"
        }
        reassembler.initializeAt(start)
        activePeers.onCursorAdvanced(start, nowMillis)
        initializedFromLiveWindow = true
        return start
    }

    fun bufferedPieceCount(): Int = reassembler.bufferedPieceCount()

    fun bufferedPayloadBytes(): Long = reassembler.bufferedPayloadBytes()

    fun ownerOf(piece: Long): Long? = activePeers.ownerOf(piece)

    private fun onLiveChunk(
        peerId: Long,
        message: AceLivePeerWireMessage.LiveChunk,
        nowMillis: Long
    ): AceLivePeerMessageResult {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = AceLiveProducerBoundaryStage.CHUNK_INGRESS,
            peerId = peerId,
            piece = message.piece,
            nowMillis = nowMillis
        )

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
            reportChunkRejected(peerId, chunk.piece, preflight.name, nowMillis)
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
                reportChunkRejected(
                    peerId,
                    chunk.piece,
                    AceLiveReassemblyDisposition.DUPLICATE.name,
                    nowMillis
                )
                return AceLivePeerMessageResult(
                    handled = true,
                    reassemblyDisposition = AceLiveReassemblyDisposition.DUPLICATE
                )
            }

            val activeDuplicate = activePeers.onChunk(
                chunk = chunk,
                nextNeeded = nextNeeded,
                nowMillis = nowMillis
            )
            return if (activeDuplicate.disposition == AceLiveChunkDisposition.DUPLICATE) {
                reportChunkRejected(
                    peerId,
                    chunk.piece,
                    activeDuplicate.disposition.name,
                    nowMillis
                )
                AceLivePeerMessageResult(
                    handled = true,
                    activeChunkDisposition = activeDuplicate.disposition,
                    reassemblyDisposition = AceLiveReassemblyDisposition.DUPLICATE
                )
            } else {
                if (activeDuplicate.accepted) {
                    reportChunkAccepted(peerId, chunk.piece, activeDuplicate.disposition.name, nowMillis)
                } else {
                    reportChunkRejected(peerId, chunk.piece, activeDuplicate.disposition.name, nowMillis)
                }
                AceLivePeerMessageResult(
                    handled = true,
                    activeChunkDisposition = activeDuplicate.disposition
                )
            }
        }

        val activeResult = activePeers.onChunk(
            chunk = chunk,
            nextNeeded = nextNeeded,
            nowMillis = nowMillis
        )
        if (!activeResult.accepted) {
            reportChunkRejected(peerId, chunk.piece, activeResult.disposition.name, nowMillis)
            return AceLivePeerMessageResult(
                handled = true,
                activeChunkDisposition = activeResult.disposition
            )
        }

        val reassembly = reassembler.appendAcceptedChunk(chunk)
        check(reassembly.accepted) {
            "Reassembly changed after successful preflight: ${reassembly.disposition}"
        }
        reportChunkAccepted(peerId, chunk.piece, reassembly.disposition.name, nowMillis)
        if (reassembly.emittedPieces.isNotEmpty()) {
            emittedAnyPiece = true
            reassembly.emittedPieces.forEach { piece ->
                reportPieceCompleted(piece, nowMillis)
            }
            synchronizeContiguousCursor(nowMillis)
        }

        return AceLivePeerMessageResult(
            handled = true,
            activeChunkDisposition = activeResult.disposition,
            reassemblyDisposition = reassembly.disposition,
            emittedPieces = reassembly.emittedPieces
        )
    }

    private fun reportChunkAccepted(
        peerId: Long,
        piece: Long,
        disposition: String,
        nowMillis: Long
    ) {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = AceLiveProducerBoundaryStage.CHUNK_ACCEPTED,
            peerId = peerId,
            piece = piece,
            disposition = disposition,
            nowMillis = nowMillis
        )
    }

    private fun reportChunkRejected(
        peerId: Long,
        piece: Long,
        disposition: String,
        nowMillis: Long
    ) {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = AceLiveProducerBoundaryStage.CHUNK_REJECTED,
            peerId = peerId,
            piece = piece,
            disposition = disposition,
            nowMillis = nowMillis
        )
    }

    private fun reportPieceCompleted(
        piece: AceLiveReassembledPiece,
        nowMillis: Long
    ) {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = AceLiveProducerBoundaryStage.PIECE_COMPLETED,
            peerId = piece.sourcePeerId,
            piece = piece.piece,
            nowMillis = nowMillis
        )
    }

    private fun reportBoundary(
        stage: AceLiveProducerBoundaryStage,
        peerId: Long?,
        piece: Long?,
        disposition: String? = null,
        bytes: Long? = null,
        acceptedChunks: Int? = null,
        assignmentAgeMillis: Long? = null,
        progressAgeMillis: Long? = null,
        nowMillis: Long
    ) {
        producerBoundaryDiagnostics.record(
            sessionId = producerBoundarySessionId,
            stage = stage,
            peerId = peerId,
            piece = piece,
            disposition = disposition,
            bytes = bytes,
            acceptedChunks = acceptedChunks,
            assignmentAgeMillis = assignmentAgeMillis,
            progressAgeMillis = progressAgeMillis,
            nowMillis = nowMillis
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

    private fun Long.saturatingSubtract(delta: Long): Long =
        if (delta >= this) 0L else this - delta

    private companion object {
        val nextProducerBoundarySessionId = AtomicLong(1L)
    }
}
