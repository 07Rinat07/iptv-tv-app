package com.iptv.tv.feature.player

internal enum class P2pPlayerBoundaryEventType(val wireName: String) {
    LOAD_STARTED("load_started"),
    LOAD_COMPLETED("load_completed"),
    LOAD_ERROR("load_error"),
    LOAD_RETRY("load_retry"),
    BUFFERING("buffering"),
    READY("ready"),
    FIRST_AUDIO("first_audio"),
    FIRST_VIDEO_FRAME("first_video_frame"),
    TERMINAL("terminal")
}

internal data class P2pLoadBoundaryEvidence(
    val taskId: Long,
    val positionBytes: Long,
    val lengthBytes: Long?,
    val bytesLoaded: Long,
    val wasCanceled: Boolean? = null,
    val errorType: String? = null
)

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
    val loadEventDurationMillis: Long = 0L,
    val loadEvidence: P2pLoadBoundaryEvidence? = null,
    val readySeen: Boolean = false,
    val firstAudioSeen: Boolean = false,
    val firstVideoFrameSeen: Boolean = false
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
 * A terminal snapshot is emitted at most once when the Media3 session leaves composition. It keeps
 * READY, first-audio and first-video evidence together with the cumulative load/rebuffer counters so
 * field analysis can count rendered sessions directly instead of inferring success from load start.
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
    private var firstAudioReported = false
    private var firstLoadStartedReported = false
    private var firstLoadCompletedReported = false
    private var terminalReported = false
    private var loadAttemptCount = 0
    private var loadCompletedCount = 0
    private var loadErrorCount = 0
    private var loadRetryCount = 0

    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(playbackStartedAtMillis >= 0L) { "playbackStartedAtMillis must be non-negative" }
    }

    fun onLoadStarted(
        nowMillis: Long,
        evidence: P2pLoadBoundaryEvidence? = null
    ): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        loadAttemptCount = saturatingIncrement(loadAttemptCount)
        if (firstLoadStartedReported) return null
        firstLoadStartedReported = true
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_STARTED,
            nowMillis = nowMillis,
            loadEvidence = evidence
        )
    }

    fun onLoadCompleted(
        nowMillis: Long,
        loadDurationMillis: Long,
        evidence: P2pLoadBoundaryEvidence? = null
    ): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        require(loadDurationMillis >= 0L) { "loadDurationMillis must be non-negative" }
        loadCompletedCount = saturatingIncrement(loadCompletedCount)
        if (firstLoadCompletedReported) return null
        firstLoadCompletedReported = true
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_COMPLETED,
            nowMillis = nowMillis,
            loadEventDurationMillis = loadDurationMillis,
            loadEvidence = evidence
        )
    }

    fun onLoadError(
        nowMillis: Long,
        loadDurationMillis: Long,
        evidence: P2pLoadBoundaryEvidence? = null
    ): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        require(loadDurationMillis >= 0L) { "loadDurationMillis must be non-negative" }
        loadErrorCount = saturatingIncrement(loadErrorCount)
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_ERROR,
            nowMillis = nowMillis,
            loadEventDurationMillis = loadDurationMillis,
            loadEvidence = evidence
        )
    }

    fun onLoadRetry(
        nowMillis: Long,
        evidence: P2pLoadBoundaryEvidence? = null
    ): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        loadRetryCount = saturatingIncrement(loadRetryCount)
        return snapshot(
            event = P2pPlayerBoundaryEventType.LOAD_RETRY,
            nowMillis = nowMillis,
            loadEvidence = evidence
        )
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

    fun onFirstAudio(nowMillis: Long): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        if (firstAudioReported) return null
        firstAudioReported = true
        return snapshot(P2pPlayerBoundaryEventType.FIRST_AUDIO, nowMillis)
    }

    fun onFirstVideoFrame(nowMillis: Long): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        if (firstFrameReported) return null
        firstFrameReported = true
        return snapshot(P2pPlayerBoundaryEventType.FIRST_VIDEO_FRAME, nowMillis)
    }

    fun onTerminal(nowMillis: Long): P2pPlayerBoundaryTelemetry? {
        validateClock(nowMillis)
        if (terminalReported) return null
        terminalReported = true

        val bufferingStartedAt = bufferingSinceMillis
        if (bufferingStartedAt != null && bufferingIsRebuffer) {
            totalRebufferDurationMillis = saturatingAdd(
                totalRebufferDurationMillis,
                elapsedSince(bufferingStartedAt, nowMillis)
            )
        }
        bufferingSinceMillis = null
        bufferingIsRebuffer = false

        return snapshot(P2pPlayerBoundaryEventType.TERMINAL, nowMillis)
    }

    private fun snapshot(
        event: P2pPlayerBoundaryEventType,
        nowMillis: Long,
        loadEventDurationMillis: Long = 0L,
        loadEvidence: P2pLoadBoundaryEvidence? = null
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
            loadEventDurationMillis = loadEventDurationMillis,
            loadEvidence = loadEvidence,
            readySeen = readySeen,
            firstAudioSeen = firstAudioReported,
            firstVideoFrameSeen = firstFrameReported
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
