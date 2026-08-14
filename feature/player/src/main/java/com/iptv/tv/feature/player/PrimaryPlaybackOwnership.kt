package com.iptv.tv.feature.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the monotonic identity of primary playback requests and concrete decoder sessions.
 *
 * A request identifies the user's latest channel intent (A -> B -> C). A session identifies one
 * concrete Media3/LibVLC instance created for that request, including retries. Keeping both counters
 * monotonic prevents a late callback/retry from an old channel from becoming valid again after the
 * current session was cleared and recreated.
 */
internal class PrimaryPlaybackOwnership {
    private val requestSequence = AtomicLong(0L)
    private val sessionSequence = AtomicLong(0L)

    fun beginRequest(): Long = requestSequence.incrementAndGet()

    fun invalidateRequest(): Long = requestSequence.incrementAndGet()

    fun isCurrentRequest(requestId: Long): Boolean = requestSequence.get() == requestId

    fun nextSessionId(): Long = sessionSequence.incrementAndGet()

    fun ownsSession(
        expectedRequestId: Long,
        expectedSessionId: Long,
        currentRequestId: Long?,
        currentSessionId: Long?
    ): Boolean {
        return isCurrentRequest(expectedRequestId) &&
            currentRequestId == expectedRequestId &&
            currentSessionId == expectedSessionId
    }
}
