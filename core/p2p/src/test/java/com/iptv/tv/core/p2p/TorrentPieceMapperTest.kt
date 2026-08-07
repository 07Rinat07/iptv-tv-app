package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class TorrentPieceMapperTest {
    @Test
    fun mapsRangeInsideFirstPieceAndAddsReadAhead() {
        assertEquals(
            TorrentPieceWindow(0, 0, 4),
            TorrentPieceMapper.map(
                fileOffsetBytes = 0L,
                rangeStartBytes = 100L,
                rangeEndInclusiveBytes = 1_000L,
                pieceLengthBytes = 16_384,
                pieceCount = 20
            )
        )
    }

    @Test
    fun mapsRangeCrossingPieceBoundary() {
        assertEquals(
            TorrentPieceWindow(0, 1, 3),
            TorrentPieceMapper.map(
                fileOffsetBytes = 0L,
                rangeStartBytes = 16_000L,
                rangeEndInclusiveBytes = 20_000L,
                pieceLengthBytes = 16_384,
                pieceCount = 20,
                readAheadPieces = 2
            )
        )
    }

    @Test
    fun includesTorrentFileOffset() {
        assertEquals(
            TorrentPieceWindow(2, 3, 4),
            TorrentPieceMapper.map(
                fileOffsetBytes = 32_000L,
                rangeStartBytes = 1_000L,
                rangeEndInclusiveBytes = 20_000L,
                pieceLengthBytes = 16_384,
                pieceCount = 10,
                readAheadPieces = 1
            )
        )
    }

    @Test
    fun capsRequestedAndReadAheadPiecesAtTorrentEnd() {
        assertEquals(
            TorrentPieceWindow(3, 3, 3),
            TorrentPieceMapper.map(
                fileOffsetBytes = 0L,
                rangeStartBytes = 49_000L,
                rangeEndInclusiveBytes = 100_000L,
                pieceLengthBytes = 16_384,
                pieceCount = 4,
                readAheadPieces = 10
            )
        )
    }

    @Test
    fun rejectsRangeStartingOutsideTorrent() {
        assertThrows(IllegalArgumentException::class.java) {
            TorrentPieceMapper.map(
                fileOffsetBytes = 0L,
                rangeStartBytes = 65_536L,
                rangeEndInclusiveBytes = 65_536L,
                pieceLengthBytes = 16_384,
                pieceCount = 4
            )
        }
    }
}
