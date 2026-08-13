package com.iptv.tv.core.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
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

    @Test
    fun `session drive does not wait for background dht refill`() = runBlocking {
        val refillStarted = CompletableDeferred<Unit>()
        var driveStartedWhileRefillWasRunning = false

        runAceLiveSessionWithBackgroundPeerRefill(
            backgroundRefill = {
                refillStarted.complete(Unit)
                awaitCancellation()
            },
            driveSession = {
                refillStarted.await()
                driveStartedWhileRefillWasRunning = true
            }
        )

        assertTrue(driveStartedWhileRefillWasRunning)
    }
}
