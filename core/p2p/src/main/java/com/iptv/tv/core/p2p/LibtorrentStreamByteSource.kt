package com.iptv.tv.core.p2p

import java.io.IOException
import java.io.RandomAccessFile
import org.libtorrent4j.Priority
import org.libtorrent4j.TorrentHandle
import org.libtorrent4j.TorrentInfo

/**
 * Bridges a selected torrent file to the loopback HTTP server.
 *
 * Reads are served only after libtorrent reports every overlapping piece as complete.
 * This prevents the player from receiving sparse/preallocated bytes that have not passed
 * libtorrent's piece verification yet.
 */
internal class LibtorrentStreamByteSource(
    private val handle: TorrentHandle,
    private val torrentInfo: TorrentInfo,
    private val fileIndex: Int,
    private val file: java.io.File,
    override val contentType: String,
    private val pieceWaitTimeoutMillis: Long = DEFAULT_PIECE_WAIT_TIMEOUT_MILLIS
) : HttpRangeByteSource {
    private val storage = torrentInfo.files()
    private val fileOffsetBytes = storage.fileOffset(fileIndex)
    private val pieceLengthBytes = torrentInfo.pieceLength()
    private val pieceCount = torrentInfo.numPieces()
    private val schedulingLock = Any()
    private val priorityWindowTracker = TorrentPriorityWindowTracker()

    override val length: Long = storage.fileSize(fileIndex)

    init {
        require(fileIndex in 0 until storage.numFiles()) { "fileIndex is outside torrent file list" }
        require(length > 0L) { "selected torrent file is empty" }
        require(pieceLengthBytes > 0) { "torrent piece length must be positive" }
        require(pieceCount > 0) { "torrent must contain pieces" }
        require(pieceWaitTimeoutMillis > 0L) { "pieceWaitTimeoutMillis must be positive" }
    }

    override fun onRangeRequested(start: Long, endInclusive: Long) {
        if (start !in 0 until length || endInclusive < start) return

        val window = mapRange(
            start = start,
            endInclusive = minOf(endInclusive, boundedEnd(start, PRIORITY_WINDOW_BYTES))
        )
        synchronized(schedulingLock) {
            priorityWindowTracker.replace(window)?.let(::resetPriorityWindowLocked)
            applyPriorityWindowLocked(window)
        }
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        require(offset >= 0 && length >= 0 && offset + length <= buffer.size) {
            "read buffer bounds are invalid"
        }
        if (length == 0) return 0
        if (position < 0L) throw IOException("Negative torrent file read position")
        if (position >= this.length) return -1
        if (!handle.isValid) throw IOException("Torrent handle is no longer valid")

        val actualLength = minOf(length.toLong(), this.length - position).toInt()
        val endInclusive = position + actualLength - 1L
        val window = scheduleRange(position, endInclusive)
        waitForPieces(window.firstRequestedPiece, window.lastRequestedPiece)

        if (!file.exists()) {
            throw IOException("Downloaded torrent file is not available on disk yet")
        }

        return RandomAccessFile(file, "r").use { randomAccess ->
            randomAccess.seek(position)
            var total = 0
            while (total < actualLength) {
                val read = randomAccess.read(buffer, offset + total, actualLength - total)
                if (read < 0) break
                total += read
            }
            if (total != actualLength) {
                throw IOException("Torrent file ended before verified range was fully readable")
            }
            total
        }
    }

    private fun scheduleRange(start: Long, endInclusive: Long): TorrentPieceWindow {
        val window = mapRange(start, endInclusive)
        synchronized(schedulingLock) {
            applyPriorityWindowLocked(window)
            priorityWindowTracker.record(window)
        }
        return window
    }

    private fun mapRange(start: Long, endInclusive: Long): TorrentPieceWindow = TorrentPieceMapper.map(
        fileOffsetBytes = fileOffsetBytes,
        rangeStartBytes = start,
        rangeEndInclusiveBytes = endInclusive,
        pieceLengthBytes = pieceLengthBytes,
        pieceCount = pieceCount,
        readAheadPieces = READ_AHEAD_PIECES
    )

    private fun resetPriorityWindowLocked(window: TorrentPieceWindow) {
        handle.clearPieceDeadlines()
        for (piece in window.firstRequestedPiece..window.lastPriorityPiece) {
            handle.piecePriority(piece, Priority.DEFAULT)
        }
    }

    private fun applyPriorityWindowLocked(window: TorrentPieceWindow) {
        handle.setSequentialRange(window.firstRequestedPiece, window.lastPriorityPiece)

        var deadlineMillis = 0
        for (piece in window.firstRequestedPiece..window.lastRequestedPiece) {
            handle.piecePriority(piece, Priority.TOP_PRIORITY)
            handle.setPieceDeadline(piece, deadlineMillis)
            deadlineMillis = (deadlineMillis + PIECE_DEADLINE_STEP_MILLIS)
                .coerceAtMost(MAX_PIECE_DEADLINE_MILLIS)
        }
        if (window.lastPriorityPiece > window.lastRequestedPiece) {
            for (piece in (window.lastRequestedPiece + 1)..window.lastPriorityPiece) {
                handle.piecePriority(piece, Priority.FIVE)
            }
        }
    }

    private fun waitForPieces(firstPiece: Int, lastPiece: Int) {
        val waitBudget = TorrentPieceWaitBudget(pieceWaitTimeoutMillis)
        for (piece in firstPiece..lastPiece) {
            while (!handle.havePiece(piece)) {
                if (!handle.isValid) throw IOException("Torrent handle became invalid while buffering")
                if (waitBudget.isExpired()) {
                    throw IOException(
                        "Timed out waiting for torrent pieces $firstPiece..$lastPiece; pending piece $piece"
                    )
                }
                try {
                    Thread.sleep(PIECE_POLL_INTERVAL_MILLIS)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Interrupted while waiting for torrent piece $piece", interrupted)
                }
            }
        }
    }

    private fun boundedEnd(start: Long, windowBytes: Long): Long {
        val maxDelta = windowBytes - 1L
        val candidate = if (start > Long.MAX_VALUE - maxDelta) Long.MAX_VALUE else start + maxDelta
        return minOf(candidate, length - 1L)
    }

    private companion object {
        const val READ_AHEAD_PIECES = 6
        const val PRIORITY_WINDOW_BYTES = 2L * 1024L * 1024L
        const val DEFAULT_PIECE_WAIT_TIMEOUT_MILLIS = 30_000L
        const val PIECE_POLL_INTERVAL_MILLIS = 50L
        const val PIECE_DEADLINE_STEP_MILLIS = 150
        const val MAX_PIECE_DEADLINE_MILLIS = 4_000
    }
}
