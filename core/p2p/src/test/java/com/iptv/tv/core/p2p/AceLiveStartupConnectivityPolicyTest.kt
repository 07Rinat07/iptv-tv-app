package com.iptv.tv.core.p2p

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveStartupConnectivityPolicyTest {
    @Test
    fun `dead swarm fails after no connection budget`() {
        assertFalse(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = false,
                anyTransportConnected = false,
                elapsedMillis = 29_999,
                timeoutMillis = 30_000
            )
        )
        assertTrue(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = false,
                anyTransportConnected = false,
                elapsedMillis = 30_000,
                timeoutMillis = 30_000
            )
        )
    }

    @Test
    fun `any real transport connection preserves the normal startup budget`() {
        assertFalse(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = false,
                anyTransportConnected = true,
                elapsedMillis = 60_000,
                timeoutMillis = 30_000
            )
        )
    }

    @Test
    fun `completed startup is never classified as dead swarm`() {
        assertFalse(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = true,
                anyTransportConnected = false,
                elapsedMillis = 60_000,
                timeoutMillis = 30_000
            )
        )
    }
}
