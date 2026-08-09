package com.iptv.tv.core.p2p

import java.nio.charset.StandardCharsets

private const val MAX_ACE_LIVE_METADATA_PIECE = 0xffff_ffffL

/** Safe subset of a decoded Ace Live peer metadata/window advertisement. */
data class AceLivePeerAdvertisedWindow(
    val minPiece: Long,
    val maxPiece: Long,
    val position: Long?,
    val distanceFromSource: Long?,
    val minPieceExplicit: Boolean
) {
    init {
        require(minPiece in 0..MAX_ACE_LIVE_METADATA_PIECE) { "minPiece must fit u32" }
        require(maxPiece in minPiece..MAX_ACE_LIVE_METADATA_PIECE) { "maxPiece must fit u32 and cover minPiece" }
    }
}

enum class AceLivePeerMetadataRejectReason {
    PAYLOAD_TOO_LARGE,
    MALFORMED_BENCODE,
    INVALID_WINDOW
}

sealed interface AceLivePeerMetadataRecognition {
    data object NotRecognized : AceLivePeerMetadataRecognition

    data class Recognized(
        val window: AceLivePeerAdvertisedWindow
    ) : AceLivePeerMetadataRecognition

    data class Rejected(
        val reason: AceLivePeerMetadataRejectReason
    ) : AceLivePeerMetadataRecognition
}

/**
 * Bounded recognizer for Ace Live `myinfo`/extended-handshake payloads.
 *
 * Classification is based on bencoded content instead of an assumed vendor message id. The
 * recognizer accepts either a direct bencoded dictionary or one leading extension/submessage byte
 * followed by a dictionary, which covers the standard BEP-10 extended-handshake envelope without
 * binding live metadata to that numeric peer-message id.
 *
 * Only `max_piece` is required. If `min_piece` is absent, the safe scheduler window is reduced to
 * the single advertised head piece rather than assuming availability of older pieces.
 */
class AceLivePeerMetadataRecognizer(
    val maxPayloadBytes: Int = DEFAULT_MAX_PAYLOAD_BYTES,
    private val maxDepth: Int = DEFAULT_MAX_DEPTH,
    private val maxContainerEntries: Int = DEFAULT_MAX_CONTAINER_ENTRIES,
    private val maxStringBytes: Int = DEFAULT_MAX_STRING_BYTES,
    private val maxTotalNodes: Int = DEFAULT_MAX_TOTAL_NODES
) {
    init {
        require(maxPayloadBytes > 0) { "maxPayloadBytes must be positive" }
        require(maxDepth > 0) { "maxDepth must be positive" }
        require(maxContainerEntries > 0) { "maxContainerEntries must be positive" }
        require(maxStringBytes > 0) { "maxStringBytes must be positive" }
        require(maxTotalNodes > 0) { "maxTotalNodes must be positive" }
    }

    fun recognize(payload: ByteArray): AceLivePeerMetadataRecognition {
        val candidateOffset = when {
            payload.isNotEmpty() && payload[0] == BENCODE_DICT -> 0
            payload.size >= 2 && payload[1] == BENCODE_DICT -> 1
            else -> return AceLivePeerMetadataRecognition.NotRecognized
        }

        val candidateSize = payload.size - candidateOffset
        if (candidateSize > maxPayloadBytes) {
            return AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.PAYLOAD_TOO_LARGE
            )
        }

        val root = runCatching {
            BoundedBencodeParser(
                data = payload,
                startOffset = candidateOffset,
                maxDepth = maxDepth,
                maxContainerEntries = maxContainerEntries,
                maxStringBytes = maxStringBytes,
                maxTotalNodes = maxTotalNodes
            ).parseRootDict()
        }.getOrElse {
            return AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.MALFORMED_BENCODE
            )
        }

        val source = (root["mi"] as? BValue.DictValue) ?: root
        val maxPiece = (source.values["max_piece"] as? BValue.IntValue)?.value
            ?: return AceLivePeerMetadataRecognition.NotRecognized
        val explicitMin = (source.values["min_piece"] as? BValue.IntValue)?.value
        val position = (source.values["position"] as? BValue.IntValue)?.value
        val distance = (source.values["distance_from_source"] as? BValue.IntValue)?.value

        if (maxPiece !in 0..MAX_ACE_LIVE_METADATA_PIECE) {
            return invalidWindow()
        }
        if (explicitMin != null && explicitMin !in 0..maxPiece) {
            return invalidWindow()
        }

        val safeMin = explicitMin ?: maxPiece
        return AceLivePeerMetadataRecognition.Recognized(
            AceLivePeerAdvertisedWindow(
                minPiece = safeMin,
                maxPiece = maxPiece,
                position = position,
                distanceFromSource = distance,
                minPieceExplicit = explicitMin != null
            )
        )
    }

    private fun invalidWindow(): AceLivePeerMetadataRecognition.Rejected =
        AceLivePeerMetadataRecognition.Rejected(AceLivePeerMetadataRejectReason.INVALID_WINDOW)

    companion object {
        const val DEFAULT_MAX_PAYLOAD_BYTES: Int = 64 * 1024
        const val DEFAULT_MAX_DEPTH: Int = 8
        const val DEFAULT_MAX_CONTAINER_ENTRIES: Int = 128
        const val DEFAULT_MAX_STRING_BYTES: Int = 32 * 1024
        const val DEFAULT_MAX_TOTAL_NODES: Int = 512

        private val BENCODE_DICT: Byte = 'd'.code.toByte()
    }
}

private sealed interface BValue {
    data class IntValue(val value: Long) : BValue
    class BytesValue(val value: ByteArray) : BValue
    data class ListValue(val values: List<BValue>) : BValue
    data class DictValue(val values: Map<String, BValue>) : BValue
}

private class BoundedBencodeParser(
    private val data: ByteArray,
    startOffset: Int,
    private val maxDepth: Int,
    private val maxContainerEntries: Int,
    private val maxStringBytes: Int,
    private val maxTotalNodes: Int
) {
    private var index: Int = startOffset
    private var totalNodes: Int = 0

    fun parseRootDict(): BValue.DictValue {
        val value = parseValue(depth = 0) as? BValue.DictValue
            ?: error("root is not a dictionary")
        require(index == data.size) { "trailing bencode bytes" }
        return value
    }

    private fun parseValue(depth: Int): BValue {
        require(depth <= maxDepth) { "bencode nesting too deep" }
        require(index < data.size) { "unexpected end of bencode" }
        totalNodes += 1
        require(totalNodes <= maxTotalNodes) { "too many bencode nodes" }

        return when (val marker = data[index].toInt() and 0xff) {
            'i'.code -> BValue.IntValue(parseInteger())
            'l'.code -> parseList(depth)
            'd'.code -> parseDict(depth)
            in '0'.code..'9'.code -> BValue.BytesValue(parseByteString())
            else -> error("invalid bencode marker: $marker")
        }
    }

    private fun parseInteger(): Long {
        expect('i')
        val start = index
        if (peek('-')) index += 1
        val digitsStart = index
        while (index < data.size && isDigit(data[index])) index += 1
        require(index > digitsStart) { "empty bencode integer" }
        require(index < data.size && data[index] == 'e'.code.toByte()) { "unterminated integer" }

        val token = String(data, start, index - start, StandardCharsets.US_ASCII)
        index += 1
        require(token != "-0") { "negative zero is not canonical" }
        val unsigned = token.removePrefix("-")
        require(unsigned == "0" || !unsigned.startsWith('0')) { "integer has leading zero" }
        return token.toLongOrNull() ?: error("integer overflow")
    }

    private fun parseList(depth: Int): BValue.ListValue {
        expect('l')
        val values = ArrayList<BValue>()
        while (!peek('e')) {
            require(values.size < maxContainerEntries) { "too many list entries" }
            values += parseValue(depth + 1)
        }
        expect('e')
        return BValue.ListValue(values)
    }

    private fun parseDict(depth: Int): BValue.DictValue {
        expect('d')
        val values = LinkedHashMap<String, BValue>()
        while (!peek('e')) {
            require(values.size < maxContainerEntries) { "too many dictionary entries" }
            require(index < data.size && isDigit(data[index])) { "dictionary key is not a byte string" }
            val keyBytes = parseByteString()
            val key = String(keyBytes, StandardCharsets.US_ASCII)
            values[key] = parseValue(depth + 1)
        }
        expect('e')
        return BValue.DictValue(values)
    }

    private fun parseByteString(): ByteArray {
        val lengthStart = index
        while (index < data.size && isDigit(data[index])) index += 1
        require(index > lengthStart) { "missing string length" }
        require(index < data.size && data[index] == ':'.code.toByte()) { "missing string separator" }

        val lengthText = String(data, lengthStart, index - lengthStart, StandardCharsets.US_ASCII)
        require(lengthText == "0" || !lengthText.startsWith('0')) { "string length has leading zero" }
        val length = lengthText.toIntOrNull() ?: error("string length overflow")
        require(length <= maxStringBytes) { "bencode string too large" }
        index += 1
        require(length <= data.size - index) { "truncated bencode string" }

        val value = data.copyOfRange(index, index + length)
        index += length
        return value
    }

    private fun expect(char: Char) {
        require(index < data.size && data[index] == char.code.toByte()) { "expected $char" }
        index += 1
    }

    private fun peek(char: Char): Boolean =
        index < data.size && data[index] == char.code.toByte()

    private fun isDigit(value: Byte): Boolean {
        val unsigned = value.toInt() and 0xff
        return unsigned in '0'.code..'9'.code
    }
}
