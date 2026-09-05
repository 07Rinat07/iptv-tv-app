package com.iptv.tv.core.p2p

/**
 * Returns true only when recovery has released timed-out piece ownership.
 *
 * Pool staleness and cursor discontinuities are deliberately excluded: both can remain true across
 * scheduler ticks and would turn an event-driven refill wakeup into a level-triggered busy loop.
 */
internal fun aceLiveRecoveryShouldWakePeerRefill(
    recovery: AceLiveRecoveryPlan
): Boolean = recovery.timedOutRequests.isNotEmpty()
