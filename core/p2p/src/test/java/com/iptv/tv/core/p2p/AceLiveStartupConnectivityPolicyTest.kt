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
                elapsedSinceFirstPeerStartMillis = 29_999,
                timeoutMillis = 30_000
            )
        )
        assertTrue(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = false,
                anyTransportConnected = false,
                elapsedSinceFirstPeerStartMillis = 30_000,
                timeoutMillis = 30_000
            )
        )
    }

    @Test
    fun `discovery time cannot consume no connection budget before first peer starts`() {
        assertFalse(
            aceLiveStartupHasNoConnectedPeerTooLong(
                startupComplete = false,
                anyTransportConnected = false,
                elapsedSinceFirstPeerStartMillis = null,
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
                elapsedSinceFirstPeerStartMillis = 60_000,
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
                elapsedSinceFirstPeerStartMillis = 60_000,
                timeoutMillis = 30_000
            )
        )
    }
}
