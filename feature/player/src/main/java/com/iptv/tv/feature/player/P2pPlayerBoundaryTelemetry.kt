package com.iptv.tv.feature.player

internal enum class P2pPlayerBoundaryEventType(val wireName: String) {
    LOAD_STARTED("load_started"),
    LOAD_COMPLETED("load_completed"),
    LOAD_ERROR("load_error"),
    LOAD_RETRY("load_retry"),
    BUFFERING("buffering"),
    READY("ready"),
    FIRST_VIDEO_FRAME("first_video_frame")
}

internal data class P2pPlayerBoundaryTelemetry(
    val sessionId: Long,
    val event: P2pPlayerBoundaryEventType,
    val elapsedSincePlaybackStartMillis: Long,
    val rebufferCount: Int,
    val totalRebufferDurationMillis: Long,
    val currentBufferingDurationMillis: Long = 0L,
    val loadAttemptCount: Int = 0,
    val loadCompletedCount: Int = 0,
    val loadErrorCount: Int = 0,
    val loadRetryCount: Int = 0,
    val loadEventDurationMillis: Long = 0L
)

/**
 * Small state machine for the Media3 side of the localhost P2P boundary.
 *
 * Startup buffering is deliberately not counted as a rebuffer. A rebuffer begins only after Media3
 * has reached READY at least once. Duplicate BUFFERING callbacks while the player is already
 * buffering do not increment the count.
 *
 * Media3 can produce many successful load callbacks while consuming a live stream. To keep the
 * bounded structured-diagnostics history useful for startup analysis, only the first LOAD_STARTED
 * and first LOAD_COMPLETED are emitted by this tracker. LOAD_ERROR and explicit LOAD_RETRY remain
 * observable because they are sparse failure/recovery evidence. Counters still advance for every
 * observed load callback so later error/retry records retain the amount of preceding load activity.
 *
 * The tracker is observational only. It owns no retry, seek, timeout, LoadControl or P2P policy.
 */
internal class P2pPlayerBoundaryTelemetryTracker(
    private val sessionId: Long,
    private val playbackStartedAtMillis: Long
) {
    private var readySeen = false
    private var bufferingSinceMillis: Long? = null
    private var bufferingIsRebuffer = false
    private var rebufferCount = 0
    private var totalRebufferDurationMillis = 0L
    private var firstFrameReported = false
    private var firstLoadStartedReported = false
    private var firstLoadCompletedReported = false
    private var loadAttemptCount = 0
    private var loadCompletedCount = 0
    private var loadErrorCount = 0
    private var loadRetryCount = 0

    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(playbackStartedAtMillis >= 0L) { "playbackStartedAtMillis must be non-negative" }
    }

    fun onLoadStarted(nowMillis: Long): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        loadAttemptCount = saturatingIncrement(loadAttemptCount)
        if (firstLoadStartedReported) return null
        firstLoadStartedReported = true
        return snapshot(P2pPlayerBoundaryEventType.LOAD_STARTED, nowMillis)
    }

    fun onLoadCompleted(
        nowMillis: Long,
        loadDurationMillis: Long
    ): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        require(loadDurationMillis >= 0L) { "loadDurationMillis must be non-negative" }
        loadCompletedCount = saturatingIncrement(loadCompletedCount)
        if (firstLoadCompletedReported) return null
        firstLoadCompletedReported = true
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_COMPLETED,
            nowMillis = nowMillis,
            loadEventDurationMillis = loadDurationMillis
        )
    }

    fun onLoadError(
        nowMillis: Long,
        loadDurationMillis: Long
    ): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        require(loadDurationMillis >= 0L) { "loadDurationMillis must be non-negative" }
        loadErrorCount = saturatingIncrement(loadErrorCount)
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_ERROR,
            nowMillis = nowMillis,
            loadEventDurationMillis = loadDurationMillis
        )
    }

    fun onLoadRetry(nowMillis: Long): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        loadRetryCount = saturatingIncrement(loadRetryCount)
        return snapshot(P2pPlayerBoundaryEventType.LOAD_RETRY, nowMillis)
    }

    fun onBuffering(nowMillis: Long): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        if (bufferingSinceMillis == null) {
            bufferingSinceMillis = nowMillis
            bufferingIsRebuffer = readySeen
            if (bufferingIsRebuffer) rebufferCount = saturatingIncrement(rebufferCount)
        }
        return snapshot(P2pPlayerBoundaryEventType.BUFFERING, nowMillis)
    }

    fun onReady(nowMillis: Long): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        val bufferingStartedAt = bufferingSinceMillis
        if (bufferingStartedAt != null && bufferingIsRebuffer) {
            totalRebufferDurationMillis = saturatingAdd(
                totalRebufferDurationMillis,
                elapsedSince(bufferingStartedAt, nowMillis)
            )
        }
        bufferingSinceMillis = null
        bufferingIsRebuffer = false
        readySeen = true
        return snapshot(P2pPlayerBoundaryEventType.READY, nowMillis)
    }

    fun onFirstVideoFrame(nowMillis: Long): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        if (firstFrameReported) return null
        firstFrameReported = true
        return snapshot(P2pPlayerBoundaryEventType.FIRST_VIDEO_FRAME, nowMillis)
    }

    private fun snapshot(
        event: P2pPlayerBoundaryEventType,
        nowMillis: Long,
        loadEventDurationMillis: Long = 0L
    ): P2pPlayerBoundaryTelemetry {
        val bufferingStartedAt = bufferingSinceMillis
        val currentBufferingDuration = if (
            bufferingStartedAt != null &&
            bufferingIsRebuffer
        ) {
            elapsedSince(bufferingStartedAt, nowMillis)
        } else {
            0L
        }
        return P2pPlayerBoundaryTelemetry(
            sessionId = sessionId,
            event = event,
            elapsedSincePlaybackStartMillis = elapsedSince(playbackStartedAtMillis, nowMillis),
            rebufferCount = rebufferCount,
            totalRebufferDurationMillis = totalRebufferDurationMillis,
            currentBufferingDurationMillis = currentBufferingDuration,
            loadAttemptCount = loadAttemptCount,
            loadCompletedCount = loadCompletedCount,
            loadErrorCount = loadErrorCount,
            loadRetryCount = loadRetryCount,
            loadEventDurationMillis = loadEventDurationMillis
        )
    }

    private fun validateClock(nowMillis: Long) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
    }

    private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
        (nowMillis - startMillis).coerceAtLeast(0L)

    private fun saturatingIncrement(value: Int): Int =
        if (value == Int.MAX_VALUE) Int.MAX_VALUE else value + 1

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
