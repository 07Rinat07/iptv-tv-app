package com.iptv.tv.core.p2p

import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class AceLiveUtpPacketType(val wireValue: Int) {
    DATA(0),
    FIN(1),
    STATE(2),
    RESET(3),
    SYN(4);

    companion object {
        fun fromWireValue(value: Int): AceLiveUtpPacketType? =
            entries.firstOrNull { type -> type.wireValue == value }
    }
}

internal class AceLiveUtpExtension(
    val type: Int,
    payload: ByteArray
) {
    val payload: ByteArray = payload.copyOf()

    init {
        require(type in 1..UByte.MAX_VALUE.toInt()) { "extension type must be in 1..255" }
        require(payload.size <= UByte.MAX_VALUE.toInt()) { "extension payload must fit one-byte length" }
        if (type == SELECTIVE_ACK_TYPE) {
            require(payload.size >= SELECTIVE_ACK_MIN_BYTES && payload.size % SELECTIVE_ACK_WORD_BYTES == 0) {
                "selective ACK payload must be at least 4 bytes and a multiple of 4"
            }
        }
    }

    companion object {
        const val SELECTIVE_ACK_TYPE: Int = 1
        private const val SELECTIVE_ACK_MIN_BYTES = 4
        private const val SELECTIVE_ACK_WORD_BYTES = 4
    }
}

internal data class AceLiveUtpHeader(
    val type: AceLiveUtpPacketType,
    val connectionId: Int,
    val timestampMicros: Long,
    val timestampDifferenceMicros: Long,
    val receiveWindowBytes: Long,
    val sequenceNumber: Int,
    val acknowledgementNumber: Int,
    val version: Int = AceLiveUtpCodec.VERSION
) {
    init {
        require(version == AceLiveUtpCodec.VERSION) { "unsupported uTP version: $version" }
        require(connectionId in 0..USHORT_MAX) { "connectionId must be uint16" }
        require(timestampMicros in 0L..UINT_MAX) { "timestampMicros must be uint32" }
        require(timestampDifferenceMicros in 0L..UINT_MAX) {
            "timestampDifferenceMicros must be uint32"
        }
        require(receiveWindowBytes in 0L..UINT_MAX) { "receiveWindowBytes must be uint32" }
        require(sequenceNumber in 0..USHORT_MAX) { "sequenceNumber must be uint16" }
        require(acknowledgementNumber in 0..USHORT_MAX) {
            "acknowledgementNumber must be uint16"
        }
    }

    private companion object {
        const val USHORT_MAX = 0xffff
        const val UINT_MAX = 0xffff_ffffL
    }
}

internal class AceLiveUtpPacket(
    val header: AceLiveUtpHeader,
    extensions: List<AceLiveUtpExtension> = emptyList(),
    payload: ByteArray = ByteArray(0)
) {
    val extensions: List<AceLiveUtpExtension> = extensions.toList()
    val payload: ByteArray = payload.copyOf()

    init {
        require(extensions.size <= AceLiveUtpCodec.MAX_EXTENSIONS) {
            "too many uTP extensions"
        }
        when (header.type) {
            AceLiveUtpPacketType.DATA -> require(payload.isNotEmpty()) {
                "ST_DATA must carry a payload"
            }
            AceLiveUtpPacketType.FIN,
            AceLiveUtpPacketType.STATE,
            AceLiveUtpPacketType.RESET,
            AceLiveUtpPacketType.SYN -> require(payload.isEmpty()) {
                "${header.type} must not carry a payload"
            }
        }
    }
}

/** Strict, allocation-bounded BEP-29 version-1 packet codec. */
internal object AceLiveUtpCodec {
    const val VERSION = 1
    const val HEADER_BYTES = 20
    const val MAX_DATAGRAM_BYTES = 1_400
    const val MAX_EXTENSIONS = 8
    const val MAX_EXTENSION_BYTES = 256

    fun encode(packet: AceLiveUtpPacket): ByteArray {
        val extensionBytes = packet.extensions.sumOf { extension -> 2 + extension.payload.size }
        require(extensionBytes <= MAX_EXTENSION_BYTES) { "uTP extension chain is too large" }
        val totalBytes = HEADER_BYTES + extensionBytes + packet.payload.size
        require(totalBytes <= MAX_DATAGRAM_BYTES) { "uTP datagram exceeds $MAX_DATAGRAM_BYTES bytes" }

        return ByteBuffer.allocate(totalBytes)
            .order(ByteOrder.BIG_ENDIAN)
            .apply {
                put(((packet.header.type.wireValue shl 4) or VERSION).toByte())
                put((packet.extensions.firstOrNull()?.type ?: 0).toByte())
                putShort(packet.header.connectionId.toShort())
                putInt(packet.header.timestampMicros.toInt())
                putInt(packet.header.timestampDifferenceMicros.toInt())
                putInt(packet.header.receiveWindowBytes.toInt())
                putShort(packet.header.sequenceNumber.toShort())
                putShort(packet.header.acknowledgementNumber.toShort())
                packet.extensions.forEachIndexed { index, extension ->
                    put((packet.extensions.getOrNull(index + 1)?.type ?: 0).toByte())
                    put(extension.payload.size.toByte())
                    put(extension.payload)
                }
                put(packet.payload)
            }
            .array()
    }

    fun decode(bytes: ByteArray): AceLiveUtpPacket? {
        if (bytes.size !in HEADER_BYTES..MAX_DATAGRAM_BYTES) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val typeAndVersion = buffer.get().toInt() and 0xff
        val type = AceLiveUtpPacketType.fromWireValue(typeAndVersion ushr 4) ?: return null
        val version = typeAndVersion and 0x0f
        if (version != VERSION) return null

        var extensionType = buffer.get().toInt() and 0xff
        val header = AceLiveUtpHeader(
            type = type,
            connectionId = buffer.short.toInt() and 0xffff,
            timestampMicros = buffer.int.toLong() and UINT_MASK,
            timestampDifferenceMicros = buffer.int.toLong() and UINT_MASK,
            receiveWindowBytes = buffer.int.toLong() and UINT_MASK,
            sequenceNumber = buffer.short.toInt() and 0xffff,
            acknowledgementNumber = buffer.short.toInt() and 0xffff,
            version = version
        )

        val extensions = ArrayList<AceLiveUtpExtension>(minOf(MAX_EXTENSIONS, 2))
        var extensionBytes = 0
        while (extensionType != 0) {
            if (extensions.size >= MAX_EXTENSIONS || buffer.remaining() < 2) return null
            val nextExtensionType = buffer.get().toInt() and 0xff
            val payloadLength = buffer.get().toInt() and 0xff
            extensionBytes += 2 + payloadLength
            if (extensionBytes > MAX_EXTENSION_BYTES || buffer.remaining() < payloadLength) return null
            val extensionPayload = ByteArray(payloadLength).also(buffer::get)
            val extension = runCatching {
                AceLiveUtpExtension(extensionType, extensionPayload)
            }.getOrNull() ?: return null
            extensions += extension
            extensionType = nextExtensionType
        }

        val payload = ByteArray(buffer.remaining()).also(buffer::get)
        return runCatching { AceLiveUtpPacket(header, extensions, payload) }.getOrNull()
    }

    private const val UINT_MASK = 0xffff_ffffL
}

internal data class AceLiveUtpClientConnectionIds(
    val receiveConnectionId: Int,
    val sendConnectionId: Int
) {
    init {
        require(receiveConnectionId in 0..USHORT_MAX)
        require(sendConnectionId in 0..USHORT_MAX)
        require(sendConnectionId == nextUint16(receiveConnectionId)) {
            "initiator send connection id must equal receive connection id + 1"
        }
    }

    companion object {
        fun fromSynConnectionId(connectionId: Int): AceLiveUtpClientConnectionIds {
            require(connectionId in 0..USHORT_MAX)
            return AceLiveUtpClientConnectionIds(
                receiveConnectionId = connectionId,
                sendConnectionId = nextUint16(connectionId)
            )
        }

        private const val USHORT_MAX = 0xffff
    }
}

internal enum class AceLiveUtpClientHandshakePhase {
    NEW,
    SYN_SENT,
    CONNECTED,
    RESET
}

/**
 * Small BEP-29 initiator handshake state machine.
 *
 * It intentionally stops at an established uTP packet stream. Datagram I/O, retransmission,
 * congestion control and adaptation to [AceLiveTcpTransport] are separate ownership layers.
 */
internal class AceLiveUtpClientHandshake(
    synConnectionId: Int,
    private val receiveWindowBytes: Long = DEFAULT_RECEIVE_WINDOW_BYTES
) {
    val connectionIds = AceLiveUtpClientConnectionIds.fromSynConnectionId(synConnectionId)

    var phase: AceLiveUtpClientHandshakePhase = AceLiveUtpClientHandshakePhase.NEW
        private set

    private var remoteSequenceNumber: Int? = null

    init {
        require(receiveWindowBytes in 1L..MAX_RECEIVE_WINDOW_BYTES)
    }

    fun createSyn(timestampMicros: Long): AceLiveUtpPacket {
        check(phase == AceLiveUtpClientHandshakePhase.NEW) { "uTP SYN has already been created" }
        phase = AceLiveUtpClientHandshakePhase.SYN_SENT
        return AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.SYN,
                connectionId = connectionIds.receiveConnectionId,
                timestampMicros = timestampMicros and UINT_MASK,
                timestampDifferenceMicros = 0,
                receiveWindowBytes = receiveWindowBytes,
                sequenceNumber = INITIAL_SEQUENCE_NUMBER,
                acknowledgementNumber = 0
            )
        )
    }

    fun acceptHandshakeResponse(packet: AceLiveUtpPacket): Boolean {
        if (phase != AceLiveUtpClientHandshakePhase.SYN_SENT) return false
        if (packet.header.connectionId != connectionIds.receiveConnectionId) return false
        if (packet.header.type == AceLiveUtpPacketType.RESET) {
            phase = AceLiveUtpClientHandshakePhase.RESET
            return false
        }
        if (
            packet.header.type != AceLiveUtpPacketType.STATE ||
            packet.header.acknowledgementNumber != INITIAL_SEQUENCE_NUMBER ||
            packet.payload.isNotEmpty()
        ) return false

        remoteSequenceNumber = packet.header.sequenceNumber
        phase = AceLiveUtpClientHandshakePhase.CONNECTED
        return true
    }

    fun createFirstData(
        payload: ByteArray,
        timestampMicros: Long,
        timestampDifferenceMicros: Long = 0
    ): AceLiveUtpPacket {
        check(phase == AceLiveUtpClientHandshakePhase.CONNECTED) { "uTP connection is not established" }
        val acknowledgement = checkNotNull(remoteSequenceNumber) {
            "remote sequence number is unavailable"
        }
        return AceLiveUtpPacket(
            header = AceLiveUtpHeader(
                type = AceLiveUtpPacketType.DATA,
                connectionId = connectionIds.sendConnectionId,
                timestampMicros = timestampMicros and UINT_MASK,
                timestampDifferenceMicros = timestampDifferenceMicros and UINT_MASK,
                receiveWindowBytes = receiveWindowBytes,
                sequenceNumber = nextUint16(INITIAL_SEQUENCE_NUMBER),
                acknowledgementNumber = acknowledgement
            ),
            payload = payload
        )
    }

    private companion object {
        const val INITIAL_SEQUENCE_NUMBER = 1
        const val DEFAULT_RECEIVE_WINDOW_BYTES = 64L * 1024L
        const val MAX_RECEIVE_WINDOW_BYTES = 4L * 1024L * 1024L
        const val UINT_MASK = 0xffff_ffffL
    }
}

private fun nextUint16(value: Int): Int = (value + 1) and 0xffff
