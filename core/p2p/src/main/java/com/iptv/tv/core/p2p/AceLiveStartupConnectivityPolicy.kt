package com.iptv.tv.core.p2p

/**
 * Startup-only fail-fast guard for a swarm that never establishes even one TCP transport.
 *
 * This is deliberately narrower than the absolute startup timeout: once any peer has connected,
 * later handshake/window/media recovery keeps the normal startup budget. The guard only eliminates
 * the long dead-swarm case where every discovered endpoint repeatedly fails to connect.
 */
internal fun aceLiveStartupHasNoConnectedPeerTooLong(
    startupComplete: Boolean,
    anyTransportConnected: Boolean,
    elapsedMillis: Long,
    timeoutMillis: Long
): Boolean {
    require(timeoutMillis > 0L) { "Ace Live no-connection timeout must be positive" }
    return !startupComplete &&
        !anyTransportConnected &&
        elapsedMillis.coerceAtLeast(0L) >= timeoutMillis
}
