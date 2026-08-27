package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StableP2pMedia3RecoveryPolicyTest {
    @Test
    fun `p2p player is isolated by session`() {
        assertEquals(
            42L,
            StableP2pMedia3RecoveryPolicy.playerLifecycleKey(
                isP2pPlayback = true,
                sessionId = 42L
            )
        )
    }

    @Test
    fun `ordinary iptv can reuse player across sessions`() {
        assertEquals(
            0L,
            StableP2pMedia3RecoveryPolicy.playerLifecycleKey(
                isP2pPlayback = false,
                sessionId = 42L
            )
        )
    }

    @Test
    fun `p2p stall must not reprepare stale localhost source`() {
        assertFalse(
            StableP2pMedia3RecoveryPolicy.shouldReprepareSameSource(isP2pPlayback = true)
        )
        assertTrue(
            StableP2pMedia3RecoveryPolicy.shouldReprepareSameSource(isP2pPlayback = false)
        )
        assertEquals(0, StableP2pMedia3RecoveryPolicy.MIN_LOADABLE_RETRY_COUNT)
    }
}
