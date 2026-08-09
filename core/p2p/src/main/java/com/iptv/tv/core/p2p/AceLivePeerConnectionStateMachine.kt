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
    val requeuedPieces: List<Long> = emptyList(),
    val emittedPieces: List<AceLiveReassembledPiece> = emptyList(),
    val disconnectRecommended: Boolean = false
)

/**
 * Per-peer connection state that can be driven by a future TCP adapter without giving core:p2p
 * ownership of sockets.
 *
 * The outer BitTorrent/Ace handshake bytes and identity/authentication remain separate because the
 * signed Ace identity contract is not implemented here. Once an adapter validates that handshake,
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
        return session.wireCodec.encodeInterestedFrame()
    }

    /**
     * Drops all connection-local state and releases scheduler ownership for this peer.
     */
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
        return requeued
    }

    /**
     * Incrementally consumes framed peer bytes after the outer handshake is accepted.
     */
    fun consumePeerBytes(bytes: ByteArray, nowMillis: Long): AceLivePeerIngressResult {
        require(phase == AceLivePeerConnectionPhase.HANDSHAKE_ACCEPTED) {
            "Ace Live peer frames require an accepted handshake"
        }
        if (protocolBlocked) {
            return AceLivePeerIngressResult(disconnectRecommended = true)
        }
        if (bytes.isEmpty()) return AceLivePeerIngressResult()

        val maxBuffered = session.wireCodec.maxFrameLengthBytes.toLong() + LENGTH_PREFIX_BYTES
        val combinedSize = receiveBuffer.size.toLong() + bytes.size.toLong()
        if (combinedSize > maxBuffered) {
            receiveBuffer = byteArrayOf()
            protocolBlocked = true
            return AceLivePeerIngressResult(disconnectRecommended = true)
        }
        receiveBuffer += bytes

        var decodedFrames = 0
        val metadataUpdates = mutableListOf<AceLivePeerAdvertisedWindow>()
        val metadataRejections = mutableListOf<AceLivePeerMetadataRejectReason>()
        val requeuedPieces = mutableListOf<Long>()
        val emittedPieces = mutableListOf<AceLiveReassembledPiece>()

        while (receiveBuffer.isNotEmpty()) {
            when (val decoded = session.decodeNext(receiveBuffer)) {
                is AceLivePeerFrameDecodeResult.NeedMoreData -> break

                is AceLivePeerFrameDecodeResult.Rejected -> {
                    receiveBuffer = byteArrayOf()
                    protocolBlocked = true
                    return AceLivePeerIngressResult(
                        decodedFrames = decodedFrames,
                        metadataUpdates = metadataUpdates,
                        metadataRejections = metadataRejections,
                        requeuedPieces = requeuedPieces,
                        emittedPieces = emittedPieces,
                        disconnectRecommended = true
                    )
                }

                is AceLivePeerFrameDecodeResult.Decoded -> {
                    decodedFrames += 1
                    receiveBuffer = receiveBuffer.copyOfRange(decoded.consumedBytes, receiveBuffer.size)
                    when (val message = decoded.message) {
                        AceLivePeerWireMessage.Choke -> {
                            peerUnchoked = false
                            session.onPeerMessage(peerId, message, nowMillis)
                        }

                        AceLivePeerWireMessage.Unchoke -> {
                            peerUnchoked = true
                            session.onPeerMessage(peerId, message, nowMillis)
                        }

                        is AceLivePeerWireMessage.Unknown -> {
                            when (val metadata = metadataRecognizer.recognize(message.payload)) {
                                AceLivePeerMetadataRecognition.NotRecognized -> {
                                    session.onPeerMessage(peerId, message, nowMillis)
                                }

                                is AceLivePeerMetadataRecognition.Rejected -> {
                                    metadataRejections += metadata.reason
                                }

                                is AceLivePeerMetadataRecognition.Recognized -> {
                                    latestWindow = metadata.window
                                    metadataUpdates += metadata.window
                                    val eventResult = session.onPeerWindow(
                                        AceLivePeerWindow(
                                            peerId = peerId,
                                            minPiece = metadata.window.minPiece,
                                            maxPiece = metadata.window.maxPiece,
                                            unchoked = peerUnchoked
                                        )
                                    )
                                    requeuedPieces += eventResult.requeuedPieces
                                }
                            }
                        }

                        else -> {
                            val result = session.onPeerMessage(peerId, message, nowMillis)
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
            requeuedPieces = requeuedPieces,
            emittedPieces = emittedPieces
        )
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
        return scheduled
            .asSequence()
            .filter { it.request.peerId == peerId }
            .map { it.bytes }
            .toList()
    }

    companion object {
        private const val LENGTH_PREFIX_BYTES = 4L
    }
}
