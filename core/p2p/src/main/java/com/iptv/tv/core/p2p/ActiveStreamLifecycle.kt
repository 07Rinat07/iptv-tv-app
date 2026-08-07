package com.iptv.tv.core.p2p

import java.io.Closeable

internal data class ActiveStreamResources<T>(
    val torrentKey: String,
    val server: Closeable,
    val torrent: T
)

/**
 * Owns resources for exactly one active P2P playback stream.
 *
 * Replacing a stream always closes the previous HTTP endpoint. The previous torrent is
 * released only when the next stream belongs to a different torrent. This allows changing
 * the selected media file inside the same torrent without tearing down its peer session.
 */
internal class ActiveStreamLifecycle<T>(
    private val releaseTorrent: (T) -> Unit
) {
    private var active: ActiveStreamResources<T>? = null

    fun replace(next: ActiveStreamResources<T>) {
        require(next.torrentKey.isNotBlank()) { "torrentKey must not be blank" }

        val previous = active
        active = next

        previous?.server?.close()
        if (previous != null && previous.torrentKey != next.torrentKey) {
            releaseTorrent(previous.torrent)
        }
    }

    fun clear() {
        val previous = active ?: return
        active = null
        previous.server.close()
        releaseTorrent(previous.torrent)
    }

    fun containsTorrent(torrentKey: String): Boolean = active?.torrentKey == torrentKey
}
