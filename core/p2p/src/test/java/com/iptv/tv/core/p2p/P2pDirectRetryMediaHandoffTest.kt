package com.iptv.tv.core.p2p

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class P2pDirectRetryMediaHandoffTest {
    @Test
    fun `media appended during fallback grace preserves retry until existing startup completes`() =
        runTest {
            val directAttempts = AtomicInteger(0)
            val retryQualification = AtomicBoolean(false)
            val retryMediaAppended = AtomicBoolean(false)
            val mediaHandoffStarted = AtomicInteger(0)
            val retryCompleted = AtomicBoolean(false)

            val result = raceP2pDirectAgainstMetadata(
                directSoftTimeoutMillis = 8_000,
                directProgressGraceMillis = 2_000,
                directHasQualificationProgress = retryQualification::get,
                directHasMediaProgress = retryMediaAppended::get,
                onDirectRetryMediaHandoffStarted = { mediaHandoffStarted.incrementAndGet() },
                directAttempt = {
                    if (directAttempts.incrementAndGet() == 1) {
                        awaitCancellation()
                    } else {
                        // Mirror the TV Box evidence: the retry is qualified just before its soft
                        // boundary, appends valid media inside the fixed 2 s grace, but needs a
                        // little longer than that grace for the unchanged startup buffer to become
                        // ready.
                        delay(7_800)
                        retryQualification.set(true)
                        delay(600)
                        retryMediaAppended.set(true)
                        delay(2_000)
                        retryCompleted.set(true)
                        P2pResult.Success("fallback-ready")
                    }
                },
                metadataResolve = { P2pResult.Success("descriptor") },
                metadataAttempt = { P2pResult.Error("metadata startup failed") },
                isCurrent = { true },
                superseded = { P2pResult.Error("superseded") },
                combinedFailureMessage = { direct, metadata ->
                    "${direct.message}; ${metadata.message}"
                }
            )

            assertTrue(result is P2pResult.Success)
            assertEquals("fallback-ready", (result as P2pResult.Success).data)
            assertEquals(2, directAttempts.get())
            assertEquals(1, mediaHandoffStarted.get())
            assertTrue(retryCompleted.get())
            assertEquals(18_400L, testScheduler.currentTime)
        }

    @Test
    fun `media handoff is not entered when fallback grace produced no media`() = runTest {
        val directAttempts = AtomicInteger(0)
        val retryQualification = AtomicBoolean(false)
        val mediaHandoffStarted = AtomicInteger(0)

        val result = raceP2pDirectAgainstMetadata(
            directSoftTimeoutMillis = 8_000,
            directProgressGraceMillis = 2_000,
            directHasQualificationProgress = retryQualification::get,
            directHasMediaProgress = { false },
            onDirectRetryMediaHandoffStarted = { mediaHandoffStarted.incrementAndGet() },
            directAttempt = {
                if (directAttempts.incrementAndGet() == 1) {
                    awaitCancellation()
                } else {
                    delay(7_800)
                    retryQualification.set(true)
                    awaitCancellation()
                }
            },
            metadataResolve = { P2pResult.Success("descriptor") },
            metadataAttempt = { P2pResult.Error("metadata startup failed") },
            isCurrent = { true },
            superseded = { P2pResult.Error("superseded") },
            combinedFailureMessage = { direct, metadata ->
                "${direct.message}; ${metadata.message}"
            }
        )

        assertTrue(result is P2pResult.Error)
        val error = result as P2pResult.Error
        assertTrue(error.message.contains("failure=qualified_peer_no_media"))
        assertEquals(0, mediaHandoffStarted.get())
        assertFalse(error.message.contains("media_appended"))
        assertEquals(18_000L, testScheduler.currentTime)
    }
}
