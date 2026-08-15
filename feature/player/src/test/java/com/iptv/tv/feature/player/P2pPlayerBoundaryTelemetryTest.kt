package com.iptv.tv.feature.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class P2pPlayerBoundaryTelemetryTest {
    @Test
    fun startupBufferingIsNotCountedAsRebuffer() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 7L,
            playbackStartedAtMillis = 1_000L
        )

        val buffering = tracker.onBuffering(nowMillis = 1_250L)
        val ready = tracker.onReady(nowMillis = 2_000L)

        assertEquals(P2pPlayerBoundaryEventType.BUFFERING, buffering.event)
        assertEquals(250L, buffering.elapsedSincePlaybackStartMillis)
        assertEquals(0, buffering.rebufferCount)
        assertEquals(0L, buffering.totalRebufferDurationMillis)
        assertEquals(0, ready.rebufferCount)
        assertEquals(0L, ready.totalRebufferDurationMillis)
    }

    @Test
    fun repeatedBufferingCallbacksCountSingleRebufferAndDuration() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 11L,
            playbackStartedAtMillis = 1_000L
        )
        tracker.onReady(nowMillis = 1_500L)

        val first = tracker.onBuffering(nowMillis = 2_000L)
        val duplicate = tracker.onBuffering(nowMillis = 2_250L)
        val recovered = tracker.onReady(nowMillis = 2_800L)

        assertEquals(1, first.rebufferCount)
        assertEquals(0L, first.currentBufferingDurationMillis)
        assertEquals(1, duplicate.rebufferCount)
        assertEquals(250L, duplicate.currentBufferingDurationMillis)
        assertEquals(1, recovered.rebufferCount)
        assertEquals(800L, recovered.totalRebufferDurationMillis)
        assertEquals(0L, recovered.currentBufferingDurationMillis)
    }

    @Test
    fun firstVideoFrameIsReportedOnce() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 13L,
            playbackStartedAtMillis = 5_000L
        )

        val first = tracker.onFirstVideoFrame(nowMillis = 5_900L)

        assertEquals(P2pPlayerBoundaryEventType.FIRST_VIDEO_FRAME, first?.event)
        assertEquals(900L, first?.elapsedSincePlaybackStartMillis)
        assertNull(tracker.onFirstVideoFrame(nowMillis = 6_100L))
    }
}
