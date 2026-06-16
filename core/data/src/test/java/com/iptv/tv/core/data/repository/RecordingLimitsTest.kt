package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingLimitsTest {

    @Test
    fun hardEndAt_usesScheduledWindowWhenItFitsLimit() {
        val startedAt = 1_000L

        val result = RecordingLimits.hardEndAt(
            startedAt = startedAt,
            scheduledEndAt = startedAt + 30L * 60L * 1000L
        )

        assertEquals(startedAt + 30L * 60L * 1000L, result)
    }

    @Test
    fun hardEndAt_capsVeryLongRecordingWindow() {
        val startedAt = 1_000L

        val result = RecordingLimits.hardEndAt(
            startedAt = startedAt,
            scheduledEndAt = startedAt + 12L * 60L * 60L * 1000L
        )

        assertEquals(startedAt + RecordingLimits.MAX_RECORDING_DURATION_MS, result)
    }

    @Test
    fun hardEndAt_fallsBackToDefaultForInvalidScheduleEnd() {
        val startedAt = 5_000L

        val result = RecordingLimits.hardEndAt(
            startedAt = startedAt,
            scheduledEndAt = startedAt
        )

        assertEquals(startedAt + RecordingLimits.DEFAULT_RECORDING_DURATION_MS, result)
    }

    @Test
    fun maxRecordingBytes_returnsZeroWhenOnlyMinimalSpaceIsAvailable() {
        val result = RecordingLimits.maxRecordingBytes(RecordingLimits.MIN_RECORDING_FREE_BYTES)

        assertEquals(0L, result)
    }

    @Test
    fun maxRecordingBytes_keepsFreeSpaceReserve() {
        val usableSpace = 2L * 1024L * 1024L * 1024L

        val result = RecordingLimits.maxRecordingBytes(usableSpace)

        assertEquals(usableSpace - RecordingLimits.FREE_SPACE_RESERVE_BYTES, result)
    }

    @Test
    fun maxRecordingBytes_appliesAbsoluteUpperBound() {
        val usableSpace = 64L * 1024L * 1024L * 1024L

        val result = RecordingLimits.maxRecordingBytes(usableSpace)

        assertEquals(RecordingLimits.ABSOLUTE_MAX_RECORDING_BYTES, result)
    }
}
