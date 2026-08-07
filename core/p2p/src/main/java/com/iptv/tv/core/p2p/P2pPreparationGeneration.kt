package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicLong

/**
 * Monotonic ownership token for embedded stream preparation.
 *
 * A newer prepare/stop action invalidates every older token immediately, allowing long-running
 * metadata work to finish without being allowed to publish a stale playback stream.
 */
internal class P2pPreparationGeneration {
    private val value = AtomicLong(0L)

    fun begin(): Long = value.incrementAndGet()

    fun invalidate(): Long = value.incrementAndGet()

    fun isCurrent(generation: Long): Boolean = value.get() == generation
}
