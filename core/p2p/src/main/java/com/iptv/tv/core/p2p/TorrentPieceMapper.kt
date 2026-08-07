package com.iptv.tv.core.p2p

internal data class TorrentPieceWindow(
    val firstRequestedPiece: Int,
    val lastRequestedPiece: Int,
    val lastPriorityPiece: Int
)

internal object TorrentPieceMapper {
    fun map(
        fileOffsetBytes: Long,
        rangeStartBytes: Long,
        rangeEndInclusiveBytes: Long,
        pieceLengthBytes: Int,
        pieceCount: Int,
        readAheadPieces: Int = DEFAULT_READ_AHEAD_PIECES
    ): TorrentPieceWindow {
        require(fileOffsetBytes >= 0L) { "fileOffsetBytes must not be negative" }
        require(rangeStartBytes >= 0L) { "rangeStartBytes must not be negative" }
        require(rangeEndInclusiveBytes >= rangeStartBytes) { "range end must not precede start" }
        require(pieceLengthBytes > 0) { "pieceLengthBytes must be positive" }
        require(pieceCount > 0) { "pieceCount must be positive" }
        require(readAheadPieces >= 0) { "readAheadPieces must not be negative" }

        val absoluteStart = addExact(fileOffsetBytes, rangeStartBytes)
        val absoluteEnd = addExact(fileOffsetBytes, rangeEndInclusiveBytes)
        val maximumAddressableByte = pieceCount.toLong() * pieceLengthBytes.toLong() - 1L
        require(absoluteStart <= maximumAddressableByte) { "range starts outside torrent pieces" }

        val firstPiece = (absoluteStart / pieceLengthBytes).toInt()
        val lastPiece = (minOf(absoluteEnd, maximumAddressableByte) / pieceLengthBytes).toInt()
        val priorityEnd = minOf(
            pieceCount - 1,
            lastPiece.toLong().plus(readAheadPieces.toLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        )

        return TorrentPieceWindow(
            firstRequestedPiece = firstPiece,
            lastRequestedPiece = lastPiece,
            lastPriorityPiece = priorityEnd
        )
    }

    private fun addExact(left: Long, right: Long): Long {
        if (right > Long.MAX_VALUE - left) {
            throw IllegalArgumentException("byte offset overflow")
        }
        return left + right
    }

    private const val DEFAULT_READ_AHEAD_PIECES = 4
}
