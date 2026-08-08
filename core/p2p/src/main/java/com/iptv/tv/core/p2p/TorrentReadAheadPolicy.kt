package com.iptv.tv.core.p2p

/**
 * Converts a byte-oriented streaming buffer target into a bounded number of torrent pieces.
 *
 * Piece sizes vary significantly between torrents, so a fixed piece count can under-buffer small
 * pieces or over-fetch large ones. The policy keeps the scheduling window byte-oriented while
 * limiting the number of outstanding priority pieces on low-resource Android TV devices.
 */
internal data class TorrentReadAheadPlan(
    val readAheadPieces: Int,
    val elevatedPriorityPieces: Int
)

internal object TorrentReadAheadPolicy {
    fun plan(
        pieceLengthBytes: Int,
        targetBytes: Long = DEFAULT_TARGET_BYTES,
        maxPieces: Int = DEFAULT_MAX_PIECES,
        elevatedPriorityPieces: Int = DEFAULT_ELEVATED_PRIORITY_PIECES
    ): TorrentReadAheadPlan {
        require(pieceLengthBytes > 0) { "pieceLengthBytes must be positive" }
        require(targetBytes > 0L) { "targetBytes must be positive" }
        require(maxPieces > 0) { "maxPieces must be positive" }
        require(elevatedPriorityPieces >= 0) { "elevatedPriorityPieces must not be negative" }

        val pieceLength = pieceLengthBytes.toLong()
        val piecesForTarget = ((targetBytes - 1L) / pieceLength + 1L)
            .coerceAtLeast(1L)
        val boundedPieces = minOf(piecesForTarget, maxPieces.toLong()).toInt()

        return TorrentReadAheadPlan(
            readAheadPieces = boundedPieces,
            elevatedPriorityPieces = minOf(elevatedPriorityPieces, boundedPieces)
        )
    }

    private const val DEFAULT_TARGET_BYTES = 8L * 1024L * 1024L
    private const val DEFAULT_MAX_PIECES = 32
    private const val DEFAULT_ELEVATED_PRIORITY_PIECES = 2
}
