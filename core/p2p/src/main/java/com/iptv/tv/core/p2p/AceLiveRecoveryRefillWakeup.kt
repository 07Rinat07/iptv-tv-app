package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Returns true only when recovery has released timed-out piece ownership.
 *
 * Pool staleness and cursor discontinuities are deliberately excluded: both can remain true across
 * scheduler ticks and would turn an event-driven refill wakeup into a level-triggered busy loop.
 */
internal fun aceLiveRecoveryShouldWakePeerRefill(
    recovery: AceLiveRecoveryPlan
): Boolean = recovery.timedOutRequests.isNotEmpty()

/**
 * Carries one bounded peer-probe demand from a timeout edge into the next serialized refill cycle.
 *
 * Repeated timeout edges coalesce while pending. Recovery demand is merged with existing adaptive
 * pressure using max semantics, so it can raise a baseline target by one peer but never stacks on
 * top of an already stronger adaptive probe request.
 */
internal class AceLiveRecoveryPeerProbe {
    private val pending = AtomicBoolean(false)

    fun request() {
        pending.set(true)
    }

    fun consumeCombinedWith(existingProbePeers: Int): Int {
        require(existingProbePeers >= 0) { "existingProbePeers must be non-negative" }
        val recoveryProbePeers = if (pending.getAndSet(false)) 1 else 0
        return maxOf(existingProbePeers, recoveryProbePeers)
    }
}
