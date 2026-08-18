package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets

/**
 * Standard BitTorrent peer handshake used only by the Content-ID metadata swarm.
 *
 * Ace live-media peers use [AceLivePeerHandshakeCodec] and its `AceStreamProtocol` wire name. The
 * metadata resolver is different: it fetches the transport descriptor through the BEP-10/BEP-9
 * extension path, which is negotiated after the standard `BitTorrent protocol` handshake. Keeping
 * this codec separate prevents the two peer-wire identities from being accidentally conflated.
 */
internal class AceContentMetadataPeerHandshakeCodec {
    fun encode(infoHash: ByteArray, peerId: ByteArray): ByteArray {
        require(infoHash.size == INFO_HASH_BYTES) { "info hash must be $INFO_HASH_BYTES bytes" }
        require(peerId.size == PEER_ID_BYTES) { "peer id must be $PEER_ID_BYTES bytes" }

        val reserved = ByteArray(RESERVED_BYTES)
        reserved[EXTENSION_RESERVED_BYTE] =
            (reserved[EXTENSION_RESERVED_BYTE].toInt() or EXTENSION_RESERVED_MASK).toByte()

        return ByteArray(HANDSHAKE_BYTES).also { output ->
            output[0] = PROTOCOL_BYTES.size.toByte()
            PROTOCOL_BYTES.copyInto(output, destinationOffset = 1)
            val reservedStart = 1 + PROTOCOL_BYTES.size
            reserved.copyInto(output, destinationOffset = reservedStart)
            val infoHashStart = reservedStart + RESERVED_BYTES
            infoHash.copyInto(output, destinationOffset = infoHashStart)
            peerId.copyInto(output, destinationOffset = infoHashStart + INFO_HASH_BYTES)
        }
    }

    fun decode(buffer: ByteArray, expectedInfoHash: ByteArray): Decoded {
        require(expectedInfoHash.size == INFO_HASH_BYTES) {
            "expected info hash must be $INFO_HASH_BYTES bytes"
        }
        require(buffer.size >= HANDSHAKE_BYTES) { "BitTorrent metadata peer handshake is incomplete" }
        require((buffer[0].toInt() and 0xff) == PROTOCOL_BYTES.size) {
            "BitTorrent metadata peer returned an invalid protocol length"
        }
        require(regionEquals(buffer, 1, PROTOCOL_BYTES)) {
            "BitTorrent metadata peer returned a different peer protocol"
        }

        val reservedStart = 1 + PROTOCOL_BYTES.size
        val infoHashStart = reservedStart + RESERVED_BYTES
        val remoteInfoHash = buffer.copyOfRange(infoHashStart, infoHashStart + INFO_HASH_BYTES)
        require(remoteInfoHash.contentEquals(expectedInfoHash)) {
            "BitTorrent metadata peer returned a different info hash"
        }

        val supportsExtensionProtocol =
            (buffer[reservedStart + EXTENSION_RESERVED_BYTE].toInt() and EXTENSION_RESERVED_MASK) != 0
        require(supportsExtensionProtocol) {
            "BitTorrent metadata peer does not advertise the extension protocol"
        }

        return Decoded(
            peerId = buffer.copyOfRange(
                infoHashStart + INFO_HASH_BYTES,
                infoHashStart + INFO_HASH_BYTES + PEER_ID_BYTES
            )
        )
    }

    private fun regionEquals(buffer: ByteArray, offset: Int, expected: ByteArray): Boolean {
        for (index in expected.indices) {
            if (buffer[offset + index] != expected[index]) return false
        }
        return true
    }

    internal data class Decoded(val peerId: ByteArray)

    companion object {
        const val RESERVED_BYTES = 8
        const val INFO_HASH_BYTES = 20
        const val PEER_ID_BYTES = 20
        const val HANDSHAKE_BYTES = 68
        const val PROTOCOL_NAME = "BitTorrent protocol"
        const val EXTENSION_RESERVED_BYTE = 5
        const val EXTENSION_RESERVED_MASK = 0x10
        private val PROTOCOL_BYTES = PROTOCOL_NAME.toByteArray(StandardCharsets.US_ASCII)
    }
}
