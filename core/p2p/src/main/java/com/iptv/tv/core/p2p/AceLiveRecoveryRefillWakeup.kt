package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicInteger

/**
 * Returns the bounded one-shot peer probe demand created by recovery.
 *
 * One timed-out piece is enough to ask for one alternative peer; multiple simultaneous timeouts do
 * not multiply socket demand. Pool staleness and cursor discontinuities are deliberately excluded:
 * both can remain true across scheduler ticks and would turn event-driven recovery into a
 * level-triggered refill loop.
 */
internal fun aceLiveRecoveryRefillProbePeers(
    recovery: AceLiveRecoveryPlan
): Int = if (recovery.timedOutRequests.isNotEmpty()) 1 else 0

/**
 * Conflates transient recovery probe demand until the serialized refill loop can consume it.
 *
 * The value is a maximum, not a sum: repeated timeout notifications cannot accumulate unbounded
 * peer demand while discovery or another refill cycle is still running.
 */
internal class AceLiveOneShotPeerProbeDemand {
    private val pending = AtomicInteger(0)

    fun request(peers: Int) {
        require(peers >= 0) { "peers must be non-negative" }
        if (peers == 0) return
        pending.accumulateAndGet(peers) { current, requested ->
            maxOf(current, requested)
        }
    }

    fun consume(): Int = pending.getAndSet(0)
}
