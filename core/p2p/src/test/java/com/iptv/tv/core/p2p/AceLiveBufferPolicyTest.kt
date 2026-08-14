package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveBufferPolicyTest {
    @Test
    fun discoveryDelayDoesNotDiluteActualMediaThroughput() {
        val policy = AceLiveStartupBufferPolicy(AceLiveBufferSettings())

        // The first media arrives only after a long discovery/handshake phase. That 15 seconds must
        // not be interpreted as time spent downloading these bytes.
        val firstMedia = policy.evaluate(
            bufferedBytes = 512L * 1024L,
            elapsedMillis = 15_000L
        )
        assertFalse(firstMedia.ready)
        assertEquals(0L, firstMedia.observedBytesPerSecond)
        assertEquals(0L, firstMedia.mediaElapsedMillis)
        assertEquals(1L * 1024L * 1024L, firstMedia.targetBytes)

        // One second later roughly another MiB has arrived. AUTO now sees the actual media delivery
        // rate and targets four seconds instead of collapsing toward the minimum buffer.
        val measured = policy.evaluate(
            bufferedBytes = 1536L * 1024L,
            elapsedMillis = 16_000L
        )
        assertFalse(measured.ready)
        assertEquals(1L * 1024L * 1024L, measured.observedBytesPerSecond)
        assertEquals(4L * 1024L * 1024L, measured.targetBytes)
        assertEquals(1_000L, measured.mediaElapsedMillis)

        val ready = policy.evaluate(
            bufferedBytes = 4608L * 1024L,
            elapsedMillis = 19_000L
        )
        assertTrue(ready.ready)
        assertEquals(4L * 1024L * 1024L, ready.targetBytes)
        assertTrue(ready.bufferedDurationMillis >= 4_000L)
    }

    @Test
    fun autoModeDoesNotExposeHdStreamAtOldHalfMegabyteFloor() {
        val policy = AceLiveStartupBufferPolicy(AceLiveBufferSettings())

        val tooSmall = policy.evaluate(
            bufferedBytes = 600L * 1024L,
            elapsedMillis = 5_000L
        )
        assertFalse(tooSmall.ready)
        assertEquals(1L * 1024L * 1024L, tooSmall.targetBytes)

        // Before a trustworthy rate sample exists, the new one-MiB floor is the earliest AUTO may
        // expose a stream. The old 512-KiB floor is no longer sufficient.
        val minimumUseful = policy.evaluate(
            bufferedBytes = 1L * 1024L * 1024L,
            elapsedMillis = 5_100L
        )
        assertTrue(minimumUseful.ready)
        assertEquals(0L, minimumUseful.observedBytesPerSecond)
    }

    @Test
    fun manualModeUsesExplicitStartupThresholdAndIsNeverForced() {
        val policy = AceLiveStartupBufferPolicy(
            AceLiveBufferSettings(
                mode = AceLiveBufferMode.MANUAL,
                manualStartupBufferBytes = 2L * 1024L * 1024L,
                forcedStartAfterMillis = 3_000L,
                forcedStartMinBufferBytes = 1L * 1024L * 1024L
            )
        )

        assertFalse(
            policy.evaluate(
                bufferedBytes = 1_900L * 1024L,
                elapsedMillis = 1_000L
            ).ready
        )
        val stillManual = policy.evaluate(
            bufferedBytes = 1_900L * 1024L,
            elapsedMillis = 5_000L
        )
        assertFalse(stillManual.ready)
        assertFalse(stillManual.forced)

        assertTrue(
            policy.evaluate(
                bufferedBytes = 2L * 1024L * 1024L,
                elapsedMillis = 5_500L
            ).ready
        )
    }

    @Test
    fun forcedStartBudgetBeginsAtFirstMediaAndRequiresStrongerFloor() {
        val policy = AceLiveStartupBufferPolicy(
            AceLiveBufferSettings(
                autoTargetDurationMillis = 10_000L,
                autoMinStartupBufferBytes = 4L * 1024L * 1024L,
                autoMaxStartupBufferBytes = 4L * 1024L * 1024L,
                forcedStartAfterMillis = 12_000L,
                forcedStartMinBufferBytes = 2L * 1024L * 1024L
            )
        )

        // Discovery already consumed 30 seconds, but forced-start must not fire on the first media.
        val firstMedia = policy.evaluate(
            bufferedBytes = 700L * 1024L,
            elapsedMillis = 30_000L
        )
        assertFalse(firstMedia.ready)
        assertFalse(firstMedia.forced)

        val stillTooSmall = policy.evaluate(
            bufferedBytes = 1500L * 1024L,
            elapsedMillis = 42_500L
        )
        assertFalse(stillTooSmall.ready)
        assertFalse(stillTooSmall.forced)

        val forced = policy.evaluate(
            bufferedBytes = 2L * 1024L * 1024L,
            elapsedMillis = 43_000L
        )
        assertTrue(forced.ready)
        assertTrue(forced.forced)
        assertTrue(forced.mediaElapsedMillis >= 12_000L)
    }

    @Test
    fun unsafeManualThresholdIsClampedBelowOutputBufferCapacity() {
        val policy = AceLiveStartupBufferPolicy(
            AceLiveBufferSettings(
                mode = AceLiveBufferMode.MANUAL,
                manualStartupBufferBytes = Long.MAX_VALUE,
                outputBufferBytes = 4 * 1024 * 1024
            )
        )

        val decision = policy.evaluate(
            bufferedBytes = (4L * 1024L * 1024L) - (512L * 1024L),
            elapsedMillis = 1_000L
        )
        assertTrue(decision.ready)
    }
}
