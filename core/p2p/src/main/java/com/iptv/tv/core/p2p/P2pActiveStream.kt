package com.iptv.tv.core.p2p

import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns resources associated with one active embedded P2P playback session.
 *
 * Closing is idempotent and always attempts to release the torrent handle even if the
 * loopback HTTP server fails while closing.
 */
internal class P2pActiveStream<H>(
    private val server: Closeable,
    val handle: H,
    private val removeHandle: (H) -> Unit
) : Closeable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(false, true)) return

        var failure: Throwable? = null
        try {
            server.close()
        } catch (error: Throwable) {
            failure = error
        }

        try {
            removeHandle(handle)
        } catch (error: Throwable) {
            if (failure == null) {
                failure = error
            } else {
                failure.addSuppressed(error)
            }
        }

        failure?.let { throw it }
    }
}
