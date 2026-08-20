package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveEmbeddedEngineTest {
    @Test
    fun timedOutPreparationClosesOnlyItsCurrentRuntime() = runBlocking {
        val generation = AtomicLong(7L)
        val mutex = Mutex()
        var closeCount = 0

        cleanupTimedOutAceLivePreparation(
            expectedGeneration = 7L,
            currentGeneration = generation::get,
            operationMutex = mutex,
            closeActive = { closeCount += 1 }
        )
        generation.set(8L)
        cleanupTimedOutAceLivePreparation(
            expectedGeneration = 7L,
            currentGeneration = generation::get,
            operationMutex = mutex,
            closeActive = { closeCount += 1 }
        )

        assertEquals(1, closeCount)
    }

    @Test
    fun timedOutPreparationRechecksOwnershipAfterWaitingForRuntimeLock() = runBlocking {
        val generation = AtomicLong(7L)
        val mutex = Mutex(locked = true)
        var closed = false

        val cleanup = launch {
            cleanupTimedOutAceLivePreparation(
                expectedGeneration = 7L,
                currentGeneration = generation::get,
                operationMutex = mutex,
                closeActive = { closed = true }
            )
        }
        yield()
        generation.set(8L)
        mutex.unlock()
        cleanup.join()

        assertFalse(closed)
    }

    @Test
    fun directStartupGraceUsesCurrentRuntimeQualityInsteadOfHistoricalTimeline() {
        assertTrue(
            aceLiveDirectStartupHasQualificationProgress(
                peers = listOf(peer(connected = true, connectedAgeMillis = 200L)),
                recentConnectionMillis = 2_000L
            )
        )
        assertFalse(
            aceLiveDirectStartupHasQualificationProgress(
                peers = listOf(
                    peer(
                        connected = false,
                        handshaked = true,
                        windowUseful = true,
                        unchoked = true,
                        connectedAgeMillis = 100L
                    )
                ),
                recentConnectionMillis = 2_000L
            )
        )
        assertFalse(
            aceLiveDirectStartupHasQualificationProgress(
                peers = listOf(
                    peer(
                        connected = true,
                        handshaked = true,
                        connectedAgeMillis = 5_000L
                    )
                ),
                recentConnectionMillis = 2_000L
            )
        )
        assertTrue(
            aceLiveDirectStartupHasQualificationProgress(
                peers = listOf(
                    peer(
                        connected = true,
                        handshaked = true,
                        windowUseful = true,
                        unchoked = true,
                        connectedAgeMillis = 5_000L
                    )
                ),
                recentConnectionMillis = 2_000L
            )
        )
    }

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

    private fun peer(
        connected: Boolean,
        handshaked: Boolean = false,
        windowUseful: Boolean = false,
        unchoked: Boolean = false,
        producing: Boolean = false,
        connectedAgeMillis: Long
    ) = AceLivePeerQualitySnapshot(
        peerId = 1L,
        connected = connected,
        handshaked = handshaked,
        windowUseful = windowUseful,
        unchoked = unchoked,
        producing = producing,
        recentBytesPerSecond = 0L,
        mediaAgeMillis = null,
        connectedAgeMillis = connectedAgeMillis,
        totalMediaBytes = 0L
    )
}
