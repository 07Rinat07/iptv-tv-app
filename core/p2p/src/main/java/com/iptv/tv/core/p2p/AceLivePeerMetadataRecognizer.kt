package com.iptv.tv.core.p2p

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
        require(position == null || position in 0..MAX_ACE_LIVE_METADATA_PIECE) {
            "position must fit u32 when present"
        }
        require(distanceFromSource == null || distanceFromSource in 0..MAX_ACE_LIVE_METADATA_PIECE) {
            "distanceFromSource must fit u32 when present"
        }
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
            AceBoundedBencodeParser(
                data = payload,
                startOffset = candidateOffset,
                maxDepth = maxDepth,
                maxContainerEntries = maxContainerEntries,
                maxStringBytes = maxStringBytes,
                maxTotalNodes = maxTotalNodes
            ).parseRootDictionary()
        }.getOrElse {
            return AceLivePeerMetadataRecognition.Rejected(
                AceLivePeerMetadataRejectReason.MALFORMED_BENCODE
            )
        }

        val source = (root.values["mi"] as? AceBencodeValue.Dictionary) ?: root
        val maxPiece = (source.values["max_piece"] as? AceBencodeValue.Integer)?.value
            ?: return AceLivePeerMetadataRecognition.NotRecognized
        val explicitMin = (source.values["min_piece"] as? AceBencodeValue.Integer)?.value
        val position = (source.values["position"] as? AceBencodeValue.Integer)?.value
        val distance = (source.values["distance_from_source"] as? AceBencodeValue.Integer)?.value

        if (maxPiece !in 0..MAX_ACE_LIVE_METADATA_PIECE) return invalidWindow()
        if (explicitMin != null && explicitMin !in 0..maxPiece) return invalidWindow()
        if (position != null && position !in 0..MAX_ACE_LIVE_METADATA_PIECE) return invalidWindow()
        if (distance != null && distance !in 0..MAX_ACE_LIVE_METADATA_PIECE) return invalidWindow()

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
        const val DEFAULT_MAX_DEPTH: Int = AceBoundedBencodeParser.DEFAULT_MAX_DEPTH
        const val DEFAULT_MAX_CONTAINER_ENTRIES: Int = AceBoundedBencodeParser.DEFAULT_MAX_CONTAINER_ENTRIES
        const val DEFAULT_MAX_STRING_BYTES: Int = 32 * 1024
        const val DEFAULT_MAX_TOTAL_NODES: Int = 512

        private val BENCODE_DICT: Byte = 'd'.code.toByte()
    }
}
