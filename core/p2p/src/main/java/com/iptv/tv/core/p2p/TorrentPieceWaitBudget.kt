package com.iptv.tv.core.p2p

/**
 * Bounds the total amount of time one player read may spend waiting for torrent pieces.
 *
 * The budget is shared by every piece covered by the same HTTP read. Without a shared budget,
 * a read spanning multiple missing pieces could wait the full timeout once per piece and block
 * Media3/LibVLC for much longer than intended.
 */
internal class TorrentPieceWaitBudget(
    timeoutMillis: Long,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val startedAtNanos = nanoTime()
    private val timeoutNanos = timeoutMillis * NANOS_PER_MILLI

    init {
        require(timeoutMillis > 0L) { "timeoutMillis must be positive" }
    }

    fun isExpired(): Boolean = nanoTime() - startedAtNanos >= timeoutNanos

    private companion object {
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
