package com.iptv.tv.feature.player

internal enum class P2pPlayerBoundaryEventType(val wireName: String) {
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
    val currentBufferingDurationMillis: Long = 0L
)

/**
 * Small state machine for the Media3 side of the localhost P2P boundary.
 *
 * Startup buffering is deliberately not counted as a rebuffer. A rebuffer begins only after Media3
 * has reached READY at least once. Duplicate BUFFERING callbacks while the player is already
 * buffering do not increment the count.
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

    init {
        require(sessionId > 0L) { "sessionId must be positive" }
        require(playbackStartedAtMillis >= 0L) { "playbackStartedAtMillis must be non-negative" }
    }

    fun onBuffering(nowMillis: Long): P2pPlayerBoundaryTelemetry {
        validateClock(nowMillis)
        if (bufferingSinceMillis == null) {
            bufferingSinceMillis = nowMillis
            bufferingIsRebuffer = readySeen
            if (bufferingIsRebuffer) rebufferCount += 1
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
        nowMillis: Long
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
            currentBufferingDurationMillis = currentBufferingDuration
        )
    }

    private fun validateClock(nowMillis: Long) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
    }

    private fun elapsedSince(startMillis: Long, nowMillis: Long): Long =
        (nowMillis - startMillis).coerceAtLeast(0L)

    private fun saturatingAdd(left: Long, right: Long): Long =
        if (Long.MAX_VALUE - left < right) Long.MAX_VALUE else left + right
}
