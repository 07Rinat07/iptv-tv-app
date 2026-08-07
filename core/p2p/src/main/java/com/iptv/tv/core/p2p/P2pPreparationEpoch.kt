package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates embedded stream preparation without holding a mutex across blocking metadata work.
 *
 * Every prepare/stop action advances the epoch. Long-running work can finish naturally, but it
 * must not publish a stream after a newer player action superseded it.
 */
internal class P2pPreparationEpoch {
    private val value = AtomicLong(0L)

    fun begin(): Long = value.incrementAndGet()

    fun cancelAll(): Long = value.incrementAndGet()

    fun isCurrent(epoch: Long): Boolean = value.get() == epoch

    fun requireCurrent(epoch: Long) {
        if (!isCurrent(epoch)) {
            throw P2pPreparationSupersededException()
        }
    }
}

internal class P2pPreparationSupersededException : IllegalStateException(
    "Embedded BitTorrent preparation was superseded by a newer player action"
)
