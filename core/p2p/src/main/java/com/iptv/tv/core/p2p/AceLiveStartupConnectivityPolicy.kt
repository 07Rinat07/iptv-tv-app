package com.iptv.tv.core.p2p

/**
 * Startup-only fail-fast guard for a swarm that never establishes even one TCP transport.
 *
 * This is deliberately narrower than the absolute startup timeout: once any peer has connected,
 * later handshake/window/media recovery keeps the normal startup budget. Discovery time is not
 * charged to this guard because tracker plus DHT fallback may legitimately consume most of the
 * budget before the TCP pool has received its first candidate. The nullable elapsed value arms the
 * guard only after that first candidate has actually been handed to the pool.
 */
internal fun aceLiveStartupHasNoConnectedPeerTooLong(
    startupComplete: Boolean,
    anyTransportConnected: Boolean,
    elapsedSinceFirstPeerStartMillis: Long?,
    timeoutMillis: Long
): Boolean {
    require(timeoutMillis > 0L) { "Ace Live no-connection timeout must be positive" }
    val elapsed = elapsedSinceFirstPeerStartMillis ?: return false
    return !startupComplete &&
        !anyTransportConnected &&
        elapsed.coerceAtLeast(0L) >= timeoutMillis
}
