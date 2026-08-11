package com.iptv.tv.core.p2p

/** Socket-neutral lifecycle for one Ace Live peer connection. */
enum class AceLivePeerConnectionPhase {
    DISCONNECTED,
    TRANSPORT_CONNECTED,
    HANDSHAKE_ACCEPTED
}

data class AceLivePeerIngressResult(
    val decodedFrames: Int = 0,
    val metadataUpdates: List<AceLivePeerAdvertisedWindow> = emptyList(),
    val metadataRejections: List<AceLivePeerMetadataRejectReason> = emptyList(),
    val activeChunkDispositions: List<AceLiveChunkDisposition> = emptyList(),
    val reassemblyDispositions: List<AceLiveReassemblyDisposition> = emptyList(),
    val unknownMessageIds: List<Int> = emptyList(),
    val unknownMessages: List<AceLiveUnknownMessageObservation> = emptyList(),
    val requeuedPieces: List<Long> = emptyList(),
    val emittedPieces: List<AceLiveReassembledPiece> = emptyList(),
    val disconnectRecommended: Boolean = false
)

data class AceLiveUnknownMessageObservation(
    val id: Int,
    val payloadBytes: Int,
    val payloadPrefixHex: String,
    val bencodeSummary: String?
)

/**
 * Per-peer connection state driven by the TCP pool without giving this state object ownership of
 * sockets.
 *
 * The outer BitTorrent/Ace handshake bytes and identity/authentication remain in the network layer.
 * Once the adapter validates that handshake,
 * [onHandshakeAccepted] emits the standard interested frame. Peer bytes can then be fed incrementally
 * through [consumePeerBytes]; partial frames remain bounded by the wire codec's frame limit.
 *
 * Unknown peer messages are offered to [AceLivePeerMetadataRecognizer]. A recognized `myinfo`/`mi`
 * window is applied to [AceLivePeerSessionCoordinator] using the current choke state. Numeric vendor
 * message ids are never assumed. Malformed metadata is ignored for scheduling and reported without
 * tearing down an otherwise valid framed connection.
 */
class AceLivePeerConnectionStateMachine(
    val peerId: Long,
    private val session: AceLivePeerSessionCoordinator,
    private val metadataRecognizer: AceLivePeerMetadataRecognizer = AceLivePeerMetadataRecognizer()
) {
    private var phase: AceLivePeerConnectionPhase = AceLivePeerConnectionPhase.DISCONNECTED
    private var peerUnchoked: Boolean = false
    private var latestWindow: AceLivePeerAdvertisedWindow? = null
    private var receiveBuffer: ByteArray = byteArrayOf()
    private var protocolBlocked: Boolean = false
    private var applicationHandshakeSent: Boolean = false

    init {
        require(peerId >= 0) { "peerId must be non-negative" }
    }

    fun phase(): AceLivePeerConnectionPhase = phase

    fun isPeerUnchoked(): Boolean = peerUnchoked

    fun advertisedWindow(): AceLivePeerAdvertisedWindow? = latestWindow

    fun advertisedHead(): Long? = latestWindow?.maxPiece

    fun isReadyForRequests(): Boolean =
        phase == AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED &&
            !protocolBlocked &&
            applicationHandshakeSent &&
            peerUnchoked &&
            latestWindow != null

    /** Called only after the transport adapter has established a fresh byte stream. */
    fun onTransportConnected() {
        require(phase == AceLivePeerConnectionPhase.DISCONNECTED) {
            "Ace Live peer transport is already connected"
        }
        phase = AceLivePeerConnectionPhase.TRANSPORT_CONNECTED
        peerUnchoked = false
        latestWindow = null
        receiveBuffer = byteArrayOf()
        protocolBlocked = false
        applicationHandshakeSent = false
    }

    /**
     * Marks the outer peer handshake as validated and returns the standard interested peer frame.
     * Handshake parsing/signing itself stays outside this increment.
     */
    fun onHandshakeAccepted(): ByteArray {
        require(phase == AceLivePeerConnectionPhase.TRANSPORT_CONNECTED) {
            "Ace Live peer handshake requires a connected transport"
        }
        phase = AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED
        applicationHandshakeSent = true
        return session.wireCodec.encodeInterestedFrame()
    }

    /** Accepts the outer handshake while waiting for the peer's live-window advertisement. */
    fun onHandshakeAcceptedWithoutInterest() {
        require(phase == AceLivePeerConnectionPhase.TRANSPORT_CONNECTED) {
            "Ace Live peer handshake requires a connected transport"
        }
        phase = AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED
        applicationHandshakeSent = false
    }

    /** Marks the signed application handshake as sent and returns the following interest frame. */
    fun onApplicationHandshakeSent(): ByteArray {
        require(phase == AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED) {
            "Ace Live application handshake requires an accepted transport handshake"
        }
        check(!applicationHandshakeSent) { "Ace Live application handshake was already sent" }
        applicationHandshakeSent = true
        return session.wireCodec.encodeInterestedFrame()
    }

    fun hasSentApplicationHandshake(): Boolean = applicationHandshakeSent

    /** Drops all connection-local state and releases scheduler ownership for this peer. */
    fun onTransportDisconnected(): AceLivePeerEventResult {
        val requeued = if (phase == AceLivePeerConnectionPhase.DISCONNECTED) {
            AceLivePeerEventResult()
        } else {
            session.onPeerDropped(peerId)
        }
        phase = AceLivePeerConnectionPhase.DISCONNECTED
        peerUnchoked = false
        latestWindow = null
        receiveBuffer = byteArrayOf()
        protocolBlocked = false
        applicationHandshakeSent = false
        return requeued
    }

    /**
     * Incrementally consumes framed peer bytes after the outer handshake is accepted.
     *
     * Complete coalesced frames are walked with a cursor over one backing array. Only an incomplete
     * trailing frame is copied into [receiveBuffer], so a batch of many small frames stays linear and
     * aggregate TCP read size is not confused with the per-frame protocol cap.
     */
    fun consumePeerBytes(bytes: ByteArray, nowMillis: Long): AceLivePeerIngressResult {
        require(phase == AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED) {
            "Ace Live peer frames require an accepted handshake"
        }
        if (protocolBlocked) {
            return AceLivePeerIngressResult(disconnectRecommended = true)
        }
        if (bytes.isEmpty()) return AceLivePeerIngressResult()

        val workingBuffer = if (receiveBuffer.isEmpty()) bytes else receiveBuffer + bytes
        receiveBuffer = byteArrayOf()

        var cursor = 0
        var decodedFrames = 0
        val metadataUpdates = mutableListOf<AceLivePeerAdvertisedWindow>()
        val metadataRejections = mutableListOf<AceLivePeerMetadataRejectReason>()
        val activeChunkDispositions = mutableListOf<AceLiveChunkDisposition>()
        val reassemblyDispositions = mutableListOf<AceLiveReassemblyDisposition>()
        val unknownMessageIds = mutableListOf<Int>()
        val unknownMessages = mutableListOf<AceLiveUnknownMessageObservation>()
        val requeuedPieces = mutableListOf<Long>()
        val emittedPieces = mutableListOf<AceLiveReassembledPiece>()

        while (cursor < workingBuffer.size) {
            when (
                val decoded = session.wireCodec.decodeNext(
                    buffer = workingBuffer,
                    offset = cursor,
                    limit = workingBuffer.size
                )
            ) {
                is AceLivePeerFrameDecodeResult.NeedMoreData -> {
                    val trailingBytes = workingBuffer.size - cursor
                    val maxRetainedBytes = session.wireCodec.maxFrameLengthBytes.toLong() + LENGTH_PREFIX_BYTES
                    if (trailingBytes.toLong() > maxRetainedBytes) {
                        protocolBlocked = true
                        return AceLivePeerIngressResult(
                            decodedFrames = decodedFrames,
                            metadataUpdates = metadataUpdates,
                            metadataRejections = metadataRejections,
                            activeChunkDispositions = activeChunkDispositions,
                            reassemblyDispositions = reassemblyDispositions,
                            unknownMessageIds = unknownMessageIds,
                            requeuedPieces = requeuedPieces,
                            emittedPieces = emittedPieces,
                            disconnectRecommended = true
                        )
                    }
                    receiveBuffer = workingBuffer.copyOfRange(cursor, workingBuffer.size)
                    break
                }

                is AceLivePeerFrameDecodeResult.Rejected -> {
                    receiveBuffer = byteArrayOf()
                    protocolBlocked = true
                    return AceLivePeerIngressResult(
                        decodedFrames = decodedFrames,
                        metadataUpdates = metadataUpdates,
                        metadataRejections = metadataRejections,
                        activeChunkDispositions = activeChunkDispositions,
                        reassemblyDispositions = reassemblyDispositions,
                        unknownMessageIds = unknownMessageIds,
                        requeuedPieces = requeuedPieces,
                        emittedPieces = emittedPieces,
                        disconnectRecommended = true
                    )
                }

                is AceLivePeerFrameDecodeResult.Decoded -> {
                    decodedFrames += 1
                    cursor += decoded.consumedBytes
                    when (val message = decoded.message) {
                        AceLivePeerWireMessage.Choke -> {
                            peerUnchoked = false
                            session.onPeerMessage(peerId, message, nowMillis)
                        }

                        AceLivePeerWireMessage.Unchoke -> {
                            peerUnchoked = true
                            session.onPeerMessage(peerId, message, nowMillis)
                        }

                        is AceLivePeerWireMessage.Have -> {
                            advanceAdvertisedWindow(message.piece)?.let { window ->
                                applyAdvertisedWindow(window, metadataUpdates, requeuedPieces)
                            }
                        }

                        is AceLivePeerWireMessage.StreamHave -> {
                            advanceAdvertisedWindow(message.piece)?.let { window ->
                                applyAdvertisedWindow(window, metadataUpdates, requeuedPieces)
                            }
                        }

                        is AceLivePeerWireMessage.LiveStatus -> {
                            applyAdvertisedWindow(
                                window = AceLivePeerAdvertisedWindow(
                                    minPiece = message.minPiece,
                                    maxPiece = message.maxPiece,
                                    position = message.position,
                                    distanceFromSource = null,
                                    minPieceExplicit = true
                                ),
                                metadataUpdates = metadataUpdates,
                                requeuedPieces = requeuedPieces
                            )
                        }

                        is AceLivePeerWireMessage.Unknown -> {
                            when (val metadata = metadataRecognizer.recognize(message.payload)) {
                                AceLivePeerMetadataRecognition.NotRecognized -> {
                                    unknownMessageIds += message.id
                                    unknownMessages += AceLiveUnknownMessageObservation(
                                        id = message.id,
                                        payloadBytes = message.payload.size,
                                        payloadPrefixHex = message.payload
                                            .take(MAX_UNKNOWN_PREFIX_BYTES)
                                            .joinToString(separator = "") { byte ->
                                                (byte.toInt() and 0xff).toString(16).padStart(2, '0')
                                            },
                                        bencodeSummary = summarizeUnknownBencode(message.payload)
                                    )
                                    session.onPeerMessage(peerId, message, nowMillis)
                                }

                                is AceLivePeerMetadataRecognition.Rejected -> {
                                    metadataRejections += metadata.reason
                                }

                                is AceLivePeerMetadataRecognition.Recognized -> {
                                    applyAdvertisedWindow(
                                        metadata.window,
                                        metadataUpdates,
                                        requeuedPieces
                                    )
                                }
                            }
                        }

                        else -> {
                            val result = session.onPeerMessage(peerId, message, nowMillis)
                            result.activeChunkDisposition?.let(activeChunkDispositions::add)
                            result.reassemblyDisposition?.let(reassemblyDispositions::add)
                            emittedPieces += result.emittedPieces
                        }
                    }
                }
            }
        }

        return AceLivePeerIngressResult(
            decodedFrames = decodedFrames,
            metadataUpdates = metadataUpdates,
            metadataRejections = metadataRejections,
            activeChunkDispositions = activeChunkDispositions,
            reassemblyDispositions = reassemblyDispositions,
            unknownMessageIds = unknownMessageIds,
            unknownMessages = unknownMessages,
            requeuedPieces = requeuedPieces,
            emittedPieces = emittedPieces
        )
    }

    private fun applyAdvertisedWindow(
        window: AceLivePeerAdvertisedWindow,
        metadataUpdates: MutableList<AceLivePeerAdvertisedWindow>,
        requeuedPieces: MutableList<Long>
    ) {
        latestWindow = window
        metadataUpdates += window
        val eventResult = session.onPeerWindow(
            AceLivePeerWindow(
                peerId = peerId,
                minPiece = window.minPiece,
                maxPiece = window.maxPiece,
                unchoked = peerUnchoked
            )
        )
        requeuedPieces += eventResult.requeuedPieces
    }

    private fun advanceAdvertisedWindow(piece: Long): AceLivePeerAdvertisedWindow? {
        val current = latestWindow ?: return null
        if (piece <= current.maxPiece) return null

        val advanced = piece - current.maxPiece
        val minPiece = if (current.minPieceExplicit) {
            (current.minPiece + advanced).coerceAtMost(piece)
        } else {
            piece
        }
        return current.copy(
            minPiece = minPiece,
            maxPiece = piece,
            position = current.position?.let { position -> maxOf(position, piece) } ?: piece
        ).also { updated -> latestWindow = updated }
    }

    /**
     * Selects already-scheduled request frames belonging to this connected, unchoked peer.
     * Global piece assignment remains in [AceLivePeerSessionCoordinator]; a future connection-pool
     * adapter routes its returned frames through the matching peer state machine.
     */
    fun selectOutboundRequestFrames(
        scheduled: List<AceLiveOutboundPeerFrame>
    ): List<ByteArray> {
        if (!isReadyForRequests()) return emptyList()
        val window = latestWindow ?: return emptyList()
        return scheduled
            .asSequence()
            .filter { outbound ->
                val request = outbound.request
                request.peerId == peerId &&
                    request.piece in window.minPiece..window.maxPiece &&
                    session.ownerOf(request.piece) == peerId
            }
            .map { it.bytes }
            .toList()
    }

    companion object {
        private const val LENGTH_PREFIX_BYTES = 4L
        private const val MAX_UNKNOWN_PREFIX_BYTES = 24
    }
}

private fun summarizeUnknownBencode(payload: ByteArray): String? = runCatching {
    val root = AceBoundedBencodeParser(payload).parseRootDictionary()
    root.values.entries.joinToString(separator = ";") { (key, value) ->
        "$key=${value.toBoundedDiagnosticValue()}"
    }
}.getOrNull()

private fun AceBencodeValue.toBoundedDiagnosticValue(): String = when (this) {
    is AceBencodeValue.Integer -> value.toString()
    is AceBencodeValue.Bytes -> "bytes[${value.size}]"
    is AceBencodeValue.ListValue -> "list[${values.size}]"
    is AceBencodeValue.Dictionary -> "dict[${values.keys.take(12).joinToString(",")}]"
}
