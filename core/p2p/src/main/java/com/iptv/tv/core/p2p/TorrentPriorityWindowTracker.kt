package com.iptv.tv.core.p2p

/**
 * Tracks the most recently scheduled torrent priority window.
 *
 * HTTP Range requests are playback boundaries: when a new request starts, the previously
 * active window is returned so native piece deadlines/priorities can be cleared before the
 * new seek target becomes time-critical. Per-read scheduling records the newest window so a
 * later seek resets the actual outstanding read-ahead region rather than an older request.
 */
internal class TorrentPriorityWindowTracker {
    private var current: TorrentPieceWindow? = null

    fun record(window: TorrentPieceWindow) {
        current = window
    }

    fun replace(window: TorrentPieceWindow): TorrentPieceWindow? {
        val previous = current
        current = window
        return previous
    }
}
