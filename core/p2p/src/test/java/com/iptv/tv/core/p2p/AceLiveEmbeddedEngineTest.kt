package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveEmbeddedEngineTest {
    @Test
    fun mediaStallRequiresCompletedStartupAndExpiredProgressDeadline() {
        assertFalse(
            aceLiveMediaIsStalled(
                startupComplete = false,
                lastMediaAtMillis = 1_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
        assertFalse(
            aceLiveMediaIsStalled(
                startupComplete = true,
                lastMediaAtMillis = 12_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
        assertTrue(
            aceLiveMediaIsStalled(
                startupComplete = true,
                lastMediaAtMillis = 11_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
    }
}
