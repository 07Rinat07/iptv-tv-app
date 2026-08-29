package com.iptv.tv.core.p2p

import java.util.ArrayDeque
import kotlin.math.max
import kotlin.math.min

internal data class AceLiveUtpCongestionPolicy(
    val minimumWindowBytes: Int,
    val initialWindowBytes: Int,
    val maximumWindowBytes: Int,
    val targetDelayMicros: Long = 100_000L,
    val baseDelayWindowMillis: Long = 120_000L,
    val maxWindowIncreaseBytesPerRtt: Int = 3_000
) {
    init {
        require(minimumWindowBytes > 0) { "minimumWindowBytes must be positive" }
        require(initialWindowBytes in minimumWindowBytes..maximumWindowBytes) {
            "initialWindowBytes must be within the congestion window bounds"
        }
        require(maximumWindowBytes in minimumWindowBytes..MAX_SAFE_WINDOW_BYTES) {
            "maximumWindowBytes must be within the safe bound"
        }
        require(targetDelayMicros in 1_000L..MAX_SAFE_TARGET_DELAY_MICROS) {
            "targetDelayMicros must be in 1000..$MAX_SAFE_TARGET_DELAY_MICROS"
        }
        require(baseDelayWindowMillis in DELAY_BUCKET_MILLIS..MAX_SAFE_DELAY_WINDOW_MILLIS) {
            "baseDelayWindowMillis must be bounded"
        }
        require(maxWindowIncreaseBytesPerRtt in 1..MAX_SAFE_WINDOW_INCREASE_BYTES) {
            "maxWindowIncreaseBytesPerRtt must be bounded"
        }
    }

    private companion object {
        const val MAX_SAFE_WINDOW_BYTES = 4 * 1024 * 1024
        const val MAX_SAFE_TARGET_DELAY_MICROS = 5L * 1_000_000L
        const val MAX_SAFE_DELAY_WINDOW_MILLIS = 10L * 60_000L
        const val MAX_SAFE_WINDOW_INCREASE_BYTES = 64 * 1024
        const val DELAY_BUCKET_MILLIS = 1_000L
    }
}

internal fun aceLiveUtpCongestionPolicy(
    sessionPolicy: AceLiveUtpSessionPolicy
): AceLiveUtpCongestionPolicy {
    val minimumWindow = minOf(sessionPolicy.maxPayloadBytes, MINIMUM_PACKET_PAYLOAD_BYTES)
    val initialWindow = minOf(
        sessionPolicy.maxInFlightBytes,
        maxOf(minimumWindow, sessionPolicy.maxPayloadBytes * INITIAL_WINDOW_PACKETS)
    )
    return AceLiveUtpCongestionPolicy(
        minimumWindowBytes = minimumWindow,
        initialWindowBytes = initialWindow,
        maximumWindowBytes = sessionPolicy.maxInFlightBytes
    )
}

/**
 * Bounded LEDBAT-shaped congestion controller for one established uTP socket.
 *
 * Delay samples are kept as one minimum per second, which bounds memory while approximating the
 * BEP-29 two-minute sliding base-delay minimum closely enough for the socket's microsecond feedback.
 * The controller does not own retransmission or fast-loss detection; callers notify it of those
 * events and use [availableWindowBytes] to gate new payload.
 */
internal class AceLiveUtpCongestionController(
    private val policy: AceLiveUtpCongestionPolicy
) {
    private data class DelayBucket(
        val startMillis: Long,
        var minimumMicros: Long
    )

    private val delayBuckets = ArrayDeque<DelayBucket>()
    private var congestionWindow = policy.initialWindowBytes.toDouble()
    private var latestBaseDelayMicros: Long? = null
    private var latestQueueDelayMicros: Long? = null
    private var lastSampleAtMillis: Long? = null

    fun availableWindowBytes(inFlightBytes: Int): Int {
        require(inFlightBytes >= 0) { "inFlightBytes must be non-negative" }
        return (congestionWindowBytes() - inFlightBytes).coerceAtLeast(0)
    }

    fun recordDelaySample(delayMicros: Long, nowMillis: Long) {
        require(delayMicros in 1L..UINT32_MAX) { "delayMicros must be a non-zero uint32" }
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }

        val previousSampleAt = lastSampleAtMillis
        if (previousSampleAt != null && nowMillis < previousSampleAt) {
            delayBuckets.clear()
        }
        lastSampleAtMillis = nowMillis

        val bucketStart = nowMillis - (nowMillis % DELAY_BUCKET_MILLIS)
        val last = delayBuckets.peekLast()
        if (last != null && last.startMillis == bucketStart) {
            last.minimumMicros = min(last.minimumMicros, delayMicros)
        } else {
            delayBuckets.addLast(DelayBucket(bucketStart, delayMicros))
        }
        pruneDelayHistory(nowMillis)

        val base = delayBuckets.minOf { bucket -> bucket.minimumMicros }
        latestBaseDelayMicros = base
        latestQueueDelayMicros = (delayMicros - base).coerceAtLeast(0L)
    }

    fun onAcknowledgement(
        acknowledgedBytes: Int,
        delaySampleMicros: Long?,
        nowMillis: Long
    ) {
        require(acknowledgedBytes > 0) { "acknowledgedBytes must be positive" }
        if (delaySampleMicros == null || delaySampleMicros == 0L) return

        recordDelaySample(delaySampleMicros, nowMillis)
        val queueDelay = latestQueueDelayMicros ?: return
        val offTarget = policy.targetDelayMicros - queueDelay
        val cappedOffTarget = min(offTarget, policy.targetDelayMicros)
        val currentWindow = congestionWindow.coerceAtLeast(1.0)
        val acknowledged = acknowledgedBytes.toDouble()
        val windowFactor = min(acknowledged, currentWindow) / max(currentWindow, acknowledged)
        val delayFactor = cappedOffTarget.toDouble() / policy.targetDelayMicros.toDouble()
        val scaledGain = policy.maxWindowIncreaseBytesPerRtt.toDouble() * windowFactor * delayFactor

        congestionWindow = (congestionWindow + scaledGain).coerceIn(
            policy.minimumWindowBytes.toDouble(),
            policy.maximumWindowBytes.toDouble()
        )
    }

    fun onPacketLoss() {
        congestionWindow = (congestionWindow * LOSS_MULTIPLIER).coerceAtLeast(
            policy.minimumWindowBytes.toDouble()
        )
    }

    fun onTimeout() {
        congestionWindow = policy.minimumWindowBytes.toDouble()
    }

    fun congestionWindowBytes(): Int =
        congestionWindow.toInt().coerceIn(policy.minimumWindowBytes, policy.maximumWindowBytes)

    fun baseDelayMicros(): Long? = latestBaseDelayMicros

    fun queueDelayMicros(): Long? = latestQueueDelayMicros

    private fun pruneDelayHistory(nowMillis: Long) {
        val cutoff = (nowMillis - policy.baseDelayWindowMillis).coerceAtLeast(0L)
        while (true) {
            val first = delayBuckets.peekFirst() ?: break
            if (first.startMillis + DELAY_BUCKET_MILLIS > cutoff) break
            delayBuckets.removeFirst()
        }

        val maxBuckets = (policy.baseDelayWindowMillis / DELAY_BUCKET_MILLIS + 2L).toInt()
        while (delayBuckets.size > maxBuckets) {
            delayBuckets.removeFirst()
        }
    }

    private companion object {
        const val UINT32_MAX = 0xffff_ffffL
        const val DELAY_BUCKET_MILLIS = 1_000L
        const val LOSS_MULTIPLIER = 0.5
    }
}

private const val MINIMUM_PACKET_PAYLOAD_BYTES = 150
private const val INITIAL_WINDOW_PACKETS = 4
