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

    @Test
    fun firstLoadStartAndCompletionAreBoundedButCountersKeepAdvancing() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 17L,
            playbackStartedAtMillis = 10_000L
        )

        val firstStart = tracker.onLoadStarted(nowMillis = 10_100L)
        val duplicateStart = tracker.onLoadStarted(nowMillis = 10_150L)
        val firstCompleted = tracker.onLoadCompleted(
            nowMillis = 10_500L,
            loadDurationMillis = 400L
        )
        val duplicateCompleted = tracker.onLoadCompleted(
            nowMillis = 10_700L,
            loadDurationMillis = 200L
        )
        val laterError = tracker.onLoadError(
            nowMillis = 10_900L,
            loadDurationMillis = 180L
        )

        assertEquals(P2pPlayerBoundaryEventType.LOAD_STARTED, firstStart?.event)
        assertEquals(1, firstStart?.loadAttemptCount)
        assertNull(duplicateStart)
        assertEquals(P2pPlayerBoundaryEventType.LOAD_COMPLETED, firstCompleted?.event)
        assertEquals(2, firstCompleted?.loadAttemptCount)
        assertEquals(1, firstCompleted?.loadCompletedCount)
        assertEquals(400L, firstCompleted?.loadEventDurationMillis)
        assertNull(duplicateCompleted)
        assertEquals(2, laterError.loadAttemptCount)
        assertEquals(2, laterError.loadCompletedCount)
        assertEquals(1, laterError.loadErrorCount)
    }

    @Test
    fun loadErrorsAndRetriesRemainObservableWithIndependentCounters() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 19L,
            playbackStartedAtMillis = 20_000L
        )
        tracker.onLoadStarted(nowMillis = 20_050L)

        val firstError = tracker.onLoadError(
            nowMillis = 20_400L,
            loadDurationMillis = 350L
        )
        val retry = tracker.onLoadRetry(nowMillis = 20_450L)
        tracker.onLoadStarted(nowMillis = 20_500L)
        val secondError = tracker.onLoadError(
            nowMillis = 20_650L,
            loadDurationMillis = 150L
        )

        assertEquals(P2pPlayerBoundaryEventType.LOAD_ERROR, firstError.event)
        assertEquals(1, firstError.loadErrorCount)
        assertEquals(350L, firstError.loadEventDurationMillis)
        assertEquals(P2pPlayerBoundaryEventType.LOAD_RETRY, retry.event)
        assertEquals(1, retry.loadRetryCount)
        assertEquals(2, secondError.loadAttemptCount)
        assertEquals(2, secondError.loadErrorCount)
        assertEquals(1, secondError.loadRetryCount)
        assertEquals(150L, secondError.loadEventDurationMillis)
    }

    @Test
    fun loadEvidenceIsPreservedOnErrorAndRetry() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 29L,
            playbackStartedAtMillis = 30_000L
        )
        val evidence = P2pLoadBoundaryEvidence(
            taskId = 77L,
            positionBytes = 1_024L,
            lengthBytes = null,
            bytesLoaded = 4_096L,
            wasCanceled = false,
            errorType = "HttpDataSourceException"
        )

        val error = tracker.onLoadError(
            nowMillis = 30_500L,
            loadDurationMillis = 450L,
            evidence = evidence
        )
        val retry = tracker.onLoadRetry(
            nowMillis = 30_510L,
            evidence = evidence
        )

        assertEquals(evidence, error.loadEvidence)
        assertEquals(evidence, retry.loadEvidence)
        assertEquals(1, error.loadErrorCount)
        assertEquals(1, retry.loadRetryCount)
    }

    @Test
    fun firstAudioIsReportedOnce() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 31L,
            playbackStartedAtMillis = 40_000L
        )

        val first = tracker.onFirstAudio(nowMillis = 40_650L)

        assertEquals(P2pPlayerBoundaryEventType.FIRST_AUDIO, first?.event)
        assertEquals(650L, first?.elapsedSincePlaybackStartMillis)
        assertNull(tracker.onFirstAudio(nowMillis = 40_900L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeLoadDurationIsRejected() {
        val tracker = P2pPlayerBoundaryTelemetryTracker(
            sessionId = 23L,
            playbackStartedAtMillis = 1_000L
        )

        tracker.onLoadError(nowMillis = 1_100L, loadDurationMillis = -1L)
    }
}
