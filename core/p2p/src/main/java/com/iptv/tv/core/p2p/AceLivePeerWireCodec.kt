package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val ACE_LIVE_WIRE_ID_CHOKE = 0
private const val ACE_LIVE_WIRE_ID_UNCHOKE = 1
private const val ACE_LIVE_WIRE_ID_INTERESTED = 2
private const val ACE_LIVE_WIRE_ID_REQUEST = 6
private const val ACE_LIVE_WIRE_ID_PIECE = 7
private const val ACE_LIVE_WIRE_PIECE_FIXED_PAYLOAD_BYTES = 18

/** Decoded peer-wire messages that are safe to consume without owning a socket. */
sealed interface AceLivePeerWireMessage {
    data object KeepAlive : AceLivePeerWireMessage
    data object Choke : AceLivePeerWireMessage
    data object Unchoke : AceLivePeerWireMessage

    class LiveChunk(
        val streamIndex: Long,
        val piece: Long,
        val pieceHeader: ByteArray,
        val chunkIndex: Int,
        val data: ByteArray
    ) : AceLivePeerWireMessage

    class Unknown(
        val id: Int,
        val payload: ByteArray
    ) : AceLivePeerWireMessage
}

enum class AceLivePeerFrameRejectReason {
    FRAME_TOO_LARGE
}

sealed interface AceLivePeerFrameDecodeResult {
    data class NeedMoreData(
        val minimumTotalBytes: Int
    ) : AceLivePeerFrameDecodeResult

    data class Decoded(
        val message: AceLivePeerWireMessage,
        val consumedBytes: Int
    ) : AceLivePeerFrameDecodeResult

    data class Rejected(
        val reason: AceLivePeerFrameRejectReason
    ) : AceLivePeerFrameDecodeResult
}

/**
 * Incremental decoder for the verified BitTorrent/Ace peer frame:
 * `<u32 big-endian body length><u8 id><payload>`, with `length=0` as keep-alive.
 *
 * The decoder is deliberately tolerant of vendor/custom ids and wrong-length standard ids: those
 * frames are preserved as [AceLivePeerWireMessage.Unknown] rather than tearing down the session.
 * The only framing-level hard rejection is an advertised body larger than [maxFrameLengthBytes],
 * which prevents an unauthenticated peer from pinning unbounded receive memory.
 */
class AceLivePeerWireCodec(
    val maxFrameLengthBytes: Int = DEFAULT_MAX_FRAME_LENGTH_BYTES
) {
    init {
        require(maxFrameLengthBytes > 0) { "maxFrameLengthBytes must be positive" }
    }

    fun decodeNext(buffer: ByteArray): AceLivePeerFrameDecodeResult {
        if (buffer.size < LENGTH_PREFIX_BYTES) {
            return AceLivePeerFrameDecodeResult.NeedMoreData(LENGTH_PREFIX_BYTES)
        }

        val bodyLength = readU32(buffer, 0)
        if (bodyLength > maxFrameLengthBytes.toLong()) {
            return AceLivePeerFrameDecodeResult.Rejected(AceLivePeerFrameRejectReason.FRAME_TOO_LARGE)
        }
        val totalLength = LENGTH_PREFIX_BYTES + bodyLength.toInt()
        if (buffer.size < totalLength) {
            return AceLivePeerFrameDecodeResult.NeedMoreData(totalLength)
        }
        if (bodyLength == 0L) {
            return AceLivePeerFrameDecodeResult.Decoded(
                message = AceLivePeerWireMessage.KeepAlive,
                consumedBytes = LENGTH_PREFIX_BYTES
            )
        }

        val id = buffer[LENGTH_PREFIX_BYTES].toInt() and 0xff
        val payloadStart = LENGTH_PREFIX_BYTES + 1
        val payloadEnd = totalLength
        val payloadLength = payloadEnd - payloadStart

        val message = when {
            id == ACE_LIVE_WIRE_ID_CHOKE && payloadLength == 0 -> AceLivePeerWireMessage.Choke
            id == ACE_LIVE_WIRE_ID_UNCHOKE && payloadLength == 0 -> AceLivePeerWireMessage.Unchoke
            id == ACE_LIVE_WIRE_ID_PIECE && payloadLength >= ACE_LIVE_WIRE_PIECE_FIXED_PAYLOAD_BYTES ->
                decodeLiveChunk(buffer, payloadStart, payloadEnd)
            else -> AceLivePeerWireMessage.Unknown(
                id = id,
                payload = buffer.copyOfRange(payloadStart, payloadEnd)
            )
        }

        return AceLivePeerFrameDecodeResult.Decoded(
            message = message,
            consumedBytes = totalLength
        )
    }

    /** Standard peer interested frame emitted only after the outer handshake is accepted. */
    fun encodeInterestedFrame(): ByteArray = byteArrayOf(
        0, 0, 0, 1,
        ACE_LIVE_WIRE_ID_INTERESTED.toByte()
    )

    /** Encodes one request produced by [AceLiveActivePeerCoordinator] as a complete peer frame. */
    fun encodeChunkRequestFrame(request: AceLiveChunkRequest): ByteArray {
        val payload = request.wirePayload()
        val bodyLength = 1 + payload.size
        return ByteBuffer.allocate(LENGTH_PREFIX_BYTES + bodyLength)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(bodyLength)
            .put(ACE_LIVE_WIRE_ID_REQUEST.toByte())
            .put(payload)
            .array()
    }

    private fun decodeLiveChunk(
        buffer: ByteArray,
        payloadStart: Int,
        payloadEnd: Int
    ): AceLivePeerWireMessage.LiveChunk {
        val streamIndex = readU32(buffer, payloadStart)
        val piece = readU32(buffer, payloadStart + 4)
        val headerStart = payloadStart + 8
        val chunkOffset = headerStart + AceLivePieceHeaderCodec.HEADER_SIZE_BYTES
        val chunkIndex = readU16(buffer, chunkOffset)
        val dataStart = chunkOffset + 2

        return AceLivePeerWireMessage.LiveChunk(
            streamIndex = streamIndex,
            piece = piece,
            pieceHeader = buffer.copyOfRange(headerStart, chunkOffset),
            chunkIndex = chunkIndex,
            data = buffer.copyOfRange(dataStart, payloadEnd)
        )
    }

    private fun readU32(buffer: ByteArray, offset: Int): Long {
        val signed = ByteBuffer.wrap(buffer, offset, 4)
            .order(ByteOrder.BIG_ENDIAN)
            .int
        return signed.toLong() and 0xffff_ffffL
    }

    private fun readU16(buffer: ByteArray, offset: Int): Int =
        ((buffer[offset].toInt() and 0xff) shl 8) or (buffer[offset + 1].toInt() and 0xff)

    companion object {
        const val DEFAULT_MAX_FRAME_LENGTH_BYTES: Int = 2 * 1024 * 1024
        private const val LENGTH_PREFIX_BYTES = 4
    }
}
