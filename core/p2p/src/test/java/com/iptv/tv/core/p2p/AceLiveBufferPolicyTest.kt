package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveBufferPolicyTest {
    @Test
    fun autoModeKeepsFastStartupBufferedForTargetDuration() {
        val policy = AceLiveStartupBufferPolicy(AceLiveBufferSettings())

        val early = policy.evaluate(
            bufferedBytes = 1L * 1024L * 1024L,
            elapsedMillis = 1_000L
        )
        assertFalse(early.ready)
        assertEquals(3L * 1024L * 1024L, early.targetBytes)

        val ready = policy.evaluate(
            bufferedBytes = 3L * 1024L * 1024L,
            elapsedMillis = 3_000L
        )
        assertTrue(ready.ready)
        assertEquals(3L * 1024L * 1024L, ready.targetBytes)
    }

    @Test
    fun autoModeAllowsSlowLiveStreamToStartWithMinimumUsefulBuffer() {
        val policy = AceLiveStartupBufferPolicy(AceLiveBufferSettings())

        val decision = policy.evaluate(
            bufferedBytes = 600L * 1024L,
            elapsedMillis = 5_000L
        )

        assertTrue(decision.ready)
        assertEquals(512L * 1024L, decision.targetBytes)
    }

    @Test
    fun manualModeUsesExplicitStartupThreshold() {
        val policy = AceLiveStartupBufferPolicy(
            AceLiveBufferSettings(
                mode = AceLiveBufferMode.MANUAL,
                manualStartupBufferBytes = 2L * 1024L * 1024L
            )
        )

        assertFalse(
            policy.evaluate(
                bufferedBytes = 1_900L * 1024L,
                elapsedMillis = 2_000L
            ).ready
        )
        assertTrue(
            policy.evaluate(
                bufferedBytes = 2L * 1024L * 1024L,
                elapsedMillis = 2_000L
            ).ready
        )
    }

    @Test
    fun forcedStartBreaksLongStartupWhenUsableMediaAlreadyExists() {
        val policy = AceLiveStartupBufferPolicy(
            AceLiveBufferSettings(
                autoTargetDurationMillis = 10_000L,
                autoMinStartupBufferBytes = 4L * 1024L * 1024L,
                autoMaxStartupBufferBytes = 4L * 1024L * 1024L,
                forcedStartAfterMillis = 20_000L,
                forcedStartMinBufferBytes = 512L * 1024L
            )
        )

        val decision = policy.evaluate(
            bufferedBytes = 700L * 1024L,
            elapsedMillis = 20_000L
        )

        assertEquals(4L * 1024L * 1024L, decision.targetBytes)
        assertTrue(decision.ready)
        assertTrue(decision.forced)
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
