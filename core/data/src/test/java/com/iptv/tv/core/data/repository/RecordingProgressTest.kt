package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class RecordingProgressTest {
    @Test
    fun recordingProgressPercent_usesElapsedTime() {
        assertEquals(0, recordingProgressPercent(startedAt = 1_000L, hardEndAt = 11_000L, now = 1_000L))
        assertEquals(50, recordingProgressPercent(startedAt = 1_000L, hardEndAt = 11_000L, now = 6_000L))
        assertEquals(99, recordingProgressPercent(startedAt = 1_000L, hardEndAt = 11_000L, now = 11_000L))
    }

    @Test
    fun recordingProgressPercent_handlesInvalidWindow() {
        assertEquals(99, recordingProgressPercent(startedAt = 10_000L, hardEndAt = 10_000L, now = 10_000L))
        assertEquals(0, recordingProgressPercent(startedAt = 10_000L, hardEndAt = 20_000L, now = 5_000L))
    }
}
