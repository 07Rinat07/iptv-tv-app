package com.iptv.tv.core.p2p

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
