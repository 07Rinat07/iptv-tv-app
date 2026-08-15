package com.iptv.tv.core.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

    @Test
    fun `startup ready cancels startup-only refill without cancelling lightweight continuation`() =
        runBlocking {
            val startup = CompletableDeferred<Unit>()
            val startupRefillStarted = CompletableDeferred<Unit>()
            val startupRefillCancelled = CompletableDeferred<Unit>()
            var lightweightRefillStarted = false

            val runner = launch {
                runAceLiveStartupRefillUntilReady(startup) {
                    startupRefillStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        startupRefillCancelled.complete(Unit)
                    }
                }
                lightweightRefillStarted = true
            }

            startupRefillStarted.await()
            startup.complete(Unit)
            withTimeout(1_000L) {
                startupRefillCancelled.await()
                runner.join()
            }

            assertTrue(lightweightRefillStarted)
            assertTrue(runner.isCompleted)
        }

    @Test
    fun `already ready startup skips startup-only refill`() = runBlocking {
        val startup = CompletableDeferred(Unit)
        var startupRefillCalled = false

        runAceLiveStartupRefillUntilReady(startup) {
            startupRefillCalled = true
        }

        assertFalse(startupRefillCalled)
    }
}
