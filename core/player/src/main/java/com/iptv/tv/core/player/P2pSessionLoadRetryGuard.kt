package com.iptv.tv.core.player

/**
 * Prevents Media3 load callbacks from retrying a P2P source after the owning
 * playback request/session has already been superseded or terminated.
 *
 * The guard is deliberately transport-agnostic: it only answers whether a
 * callback still belongs to the currently active P2P playback identity.
 */
internal class P2pSessionLoadRetryGuard {
    private var active: Identity? = null

    @Synchronized
    fun activate(sessionId: Long, requestId: Long) {
        active = Identity(sessionId = sessionId, requestId = requestId)
    }

    @Synchronized
    fun deactivate(sessionId: Long, requestId: Long) {
        if (active == Identity(sessionId = sessionId, requestId = requestId)) {
            active = null
        }
    }

    @Synchronized
    fun isActive(sessionId: Long, requestId: Long): Boolean =
        active == Identity(sessionId = sessionId, requestId = requestId)

    private data class Identity(
        val sessionId: Long,
        val requestId: Long,
    )
}
