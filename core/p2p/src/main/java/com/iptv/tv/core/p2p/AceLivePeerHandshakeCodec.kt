package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets

/** Parsed public Ace peer handshake fields. Byte arrays are defensively copied. */
class AceLivePeerHandshake(
    reservedBytes: ByteArray,
    swarmKeyBytes: ByteArray,
    peerIdBytes: ByteArray
) {
    private val reserved = reservedBytes.copyOf()
    private val swarmKey = swarmKeyBytes.copyOf()
    private val peerId = peerIdBytes.copyOf()

    init {
        require(reserved.size == AceLivePeerHandshakeCodec.RESERVED_BYTES) {
            "reserved bytes must be ${AceLivePeerHandshakeCodec.RESERVED_BYTES} bytes"
        }
        require(swarmKey.size == AceLivePeerHandshakeCodec.SWARM_KEY_BYTES) {
            "swarm key must be ${AceLivePeerHandshakeCodec.SWARM_KEY_BYTES} bytes"
        }
        require(peerId.size == AceLivePeerHandshakeCodec.PEER_ID_BYTES) {
            "peer id must be ${AceLivePeerHandshakeCodec.PEER_ID_BYTES} bytes"
        }
    }

    fun reservedBytes(): ByteArray = reserved.copyOf()

    fun swarmKeyBytes(): ByteArray = swarmKey.copyOf()

    fun peerIdBytes(): ByteArray = peerId.copyOf()
}

enum class AceLivePeerHandshakeRejectReason {
    INVALID_PROTOCOL_LENGTH,
    PROTOCOL_MISMATCH,
    SWARM_KEY_MISMATCH
}

sealed interface AceLivePeerHandshakeDecodeResult {
    data class NeedMoreData(
        val minimumTotalBytes: Int
    ) : AceLivePeerHandshakeDecodeResult

    data class Decoded(
        val handshake: AceLivePeerHandshake,
        val consumedBytes: Int
    ) : AceLivePeerHandshakeDecodeResult

    data class Rejected(
        val reason: AceLivePeerHandshakeRejectReason
    ) : AceLivePeerHandshakeDecodeResult
}

/**
 * Pure codec for the public Ace peer handshake.
 *
 * Wire layout is fixed to:
 * `[pstrlen=17]["AceStreamProtocol"][reserved 8][swarm key 20][peer id 20]`.
 *
 * This boundary deliberately does not generate an Ace identity, sign peer ids, interpret proprietary
 * authentication fields, or infer capabilities from reserved bytes. A network adapter supplies the
 * 20-byte local peer id and can feed the decoded handshake into the connection state machine only
 * after the expected swarm key has been validated here.
 */
class AceLivePeerHandshakeCodec {
    fun decode(
        buffer: ByteArray,
        expectedSwarmKey: ByteArray? = null
    ): AceLivePeerHandshakeDecodeResult {
        if (expectedSwarmKey != null) {
            require(expectedSwarmKey.size == SWARM_KEY_BYTES) {
                "expected swarm key must be $SWARM_KEY_BYTES bytes"
            }
        }

        if (buffer.isEmpty()) {
            return AceLivePeerHandshakeDecodeResult.NeedMoreData(1)
        }

        val protocolLength = buffer[0].toInt() and 0xff
        if (protocolLength != PROTOCOL_BYTES.size) {
            return AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.INVALID_PROTOCOL_LENGTH
            )
        }

        val protocolEnd = 1 + PROTOCOL_BYTES.size
        if (buffer.size < protocolEnd) {
            return AceLivePeerHandshakeDecodeResult.NeedMoreData(protocolEnd)
        }
        if (!regionEquals(buffer, 1, PROTOCOL_BYTES)) {
            return AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.PROTOCOL_MISMATCH
            )
        }

        if (buffer.size < HANDSHAKE_BYTES) {
            return AceLivePeerHandshakeDecodeResult.NeedMoreData(HANDSHAKE_BYTES)
        }

        val reservedStart = protocolEnd
        val swarmStart = reservedStart + RESERVED_BYTES
        val peerIdStart = swarmStart + SWARM_KEY_BYTES
        val swarmKey = buffer.copyOfRange(swarmStart, peerIdStart)

        if (expectedSwarmKey != null && !swarmKey.contentEquals(expectedSwarmKey)) {
            return AceLivePeerHandshakeDecodeResult.Rejected(
                AceLivePeerHandshakeRejectReason.SWARM_KEY_MISMATCH
            )
        }

        return AceLivePeerHandshakeDecodeResult.Decoded(
            handshake = AceLivePeerHandshake(
                reservedBytes = buffer.copyOfRange(reservedStart, swarmStart),
                swarmKeyBytes = swarmKey,
                peerIdBytes = buffer.copyOfRange(peerIdStart, HANDSHAKE_BYTES)
            ),
            consumedBytes = HANDSHAKE_BYTES
        )
    }

    fun encode(
        swarmKey: ByteArray,
        peerId: ByteArray,
        reserved: ByteArray = extensionProtocolReservedBytes()
    ): ByteArray {
        require(swarmKey.size == SWARM_KEY_BYTES) { "swarm key must be $SWARM_KEY_BYTES bytes" }
        require(peerId.size == PEER_ID_BYTES) { "peer id must be $PEER_ID_BYTES bytes" }
        require(reserved.size == RESERVED_BYTES) { "reserved bytes must be $RESERVED_BYTES bytes" }

        val output = ByteArray(HANDSHAKE_BYTES)
        output[0] = PROTOCOL_BYTES.size.toByte()
        PROTOCOL_BYTES.copyInto(output, destinationOffset = 1)

        val reservedStart = 1 + PROTOCOL_BYTES.size
        reserved.copyInto(output, destinationOffset = reservedStart)
        val swarmStart = reservedStart + RESERVED_BYTES
        swarmKey.copyInto(output, destinationOffset = swarmStart)
        peerId.copyInto(output, destinationOffset = swarmStart + SWARM_KEY_BYTES)
        return output
    }

    private fun regionEquals(buffer: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (index in expected.indices) {
            if (buffer[offset + index] != expected[index]) return false
        }
        return true
    }

    companion object {
        const val RESERVED_BYTES: Int = 8
        const val SWARM_KEY_BYTES: Int = 20
        const val PEER_ID_BYTES: Int = 20
        const val HANDSHAKE_BYTES: Int = 66

        const val PROTOCOL_NAME: String = "AceStreamProtocol"
        const val EXTENSION_PROTOCOL_RESERVED_INDEX: Int = 5
        const val EXTENSION_PROTOCOL_RESERVED_MASK: Int = 0x10

        internal fun extensionProtocolReservedBytes(): ByteArray =
            ByteArray(RESERVED_BYTES).also { reserved ->
                reserved[EXTENSION_PROTOCOL_RESERVED_INDEX] = EXTENSION_PROTOCOL_RESERVED_MASK.toByte()
            }

        private val PROTOCOL_BYTES: ByteArray = PROTOCOL_NAME.toByteArray(StandardCharsets.US_ASCII)
    }
}
