package com.iptv.tv.core.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class P2pSessionLoadRetryGuardTest {
    @Test
    fun `only current session and request are active`() {
        val guard = P2pSessionLoadRetryGuard()

        guard.activate(sessionId = 10, requestId = 20)

        assertTrue(guard.isActive(sessionId = 10, requestId = 20))
        assertFalse(guard.isActive(sessionId = 9, requestId = 20))
        assertFalse(guard.isActive(sessionId = 10, requestId = 19))
    }

    @Test
    fun `stale deactivation cannot clear newer playback`() {
        val guard = P2pSessionLoadRetryGuard()
        guard.activate(sessionId = 10, requestId = 20)
        guard.activate(sessionId = 11, requestId = 21)

        guard.deactivate(sessionId = 10, requestId = 20)

        assertTrue(guard.isActive(sessionId = 11, requestId = 21))
    }

    @Test
    fun `matching deactivation makes callbacks stale`() {
        val guard = P2pSessionLoadRetryGuard()
        guard.activate(sessionId = 10, requestId = 20)

        guard.deactivate(sessionId = 10, requestId = 20)

        assertFalse(guard.isActive(sessionId = 10, requestId = 20))
    }
}
