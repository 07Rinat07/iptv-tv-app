package com.iptv.tv.core.p2p

import java.util.Locale

/**
 * Converts the immutable peer-production snapshot into a bounded persistent diagnostics stream.
 *
 * Discovery/connection/handshake changes are lifecycle evidence and are emitted immediately.
 * Requestability and production can legitimately oscillate at the live-window boundary every few
 * hundred milliseconds, so those volatile stages are retained in the payload but refreshed only
 * periodically. This prevents steady-state live churn from evicting startup/zap evidence from the
 * bounded structured-diagnostics history.
 *
 * A separate producer-gap event makes the important V4d boundary explicit: at least one peer is
 * handshaked, advertises a useful live window and is unchoked, but no peer has yet delivered media
 * across the authenticated live-output boundary. The gap is emitted on entry, periodically while it
 * persists, and once on resolution. It is observational only and cannot change scheduling/refill.
 */
internal class AceLivePeerDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS,
    private val context: AceLiveRuntimeDiagnosticsContext? = null
) {
    private var lastLifecycleSignature: LifecycleSignature? = null
    private var lastReportedAtMillis: Long? = null
    private var producerGapActive: Boolean = false
    private var lastProducerGapReportedAtMillis: Long? = null

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
    }

    fun maybeReport(snapshot: AceLivePeerProductionSnapshot, nowMillis: Long) {
        val now = nowMillis.coerceAtLeast(0L)
        val lifecycleSignature = LifecycleSignature.from(snapshot)
        val previousSignature = lastLifecycleSignature
        val previousReportAt = lastReportedAtMillis
        val materialLifecycleChange = previousSignature == null || previousSignature != lifecycleSignature
        val periodicRefresh = previousReportAt == null || now - previousReportAt >= periodicIntervalMillis

        if (materialLifecycleChange || periodicRefresh) {
            lastLifecycleSignature = lifecycleSignature
            lastReportedAtMillis = now
            runCatching {
                observer(QUALITY_STATUS, formatMessage(snapshot))
            }
        }

        maybeReportProducerGap(snapshot, now)
    }

    internal fun formatMessage(snapshot: AceLivePeerProductionSnapshot): String {
        val aggregateBytesPerSecond = snapshot.aggregateBytesPerSecond.coerceAtLeast(0L)
        val aggregateMegabitsPerSecond = aggregateBytesPerSecond.toDouble() * BITS_PER_BYTE / BITS_PER_MEGABIT
        val freshestMediaAge = snapshot.freshestMediaAgeMillis
            ?.coerceAtLeast(0L)
            ?.toString()
            ?: "none"
        return buildString {
            append("discovered=")
            append(snapshot.discoveredCandidates)
            append(" connected=")
            append(snapshot.connectedPeers)
            append(" handshaked=")
            append(snapshot.handshakedPeers)
            append(" windowUseful=")
            append(snapshot.windowUsefulPeers)
            append(" unchoked=")
            append(snapshot.unchokedPeers)
            append(" producing=")
            append(snapshot.producingPeers)
            append(" aggregate_bps=")
            append(aggregateBytesPerSecond)
            append(" aggregate_mbps=")
            append(String.format(Locale.US, "%.3f", aggregateMegabitsPerSecond))
            append(" freshest_media_age_ms=")
            append(freshestMediaAge)
            appendRuntimeContext()
        }
    }

    private fun maybeReportProducerGap(snapshot: AceLivePeerProductionSnapshot, nowMillis: Long) {
        val gapNow = snapshot.handshakedPeers > 0 &&
            snapshot.windowUsefulPeers > 0 &&
            snapshot.unchokedPeers > 0 &&
            snapshot.producingPeers == 0

        if (gapNow) {
            val previousGapReportAt = lastProducerGapReportedAtMillis
            val enteringGap = !producerGapActive
            val periodicRefresh = previousGapReportAt == null ||
                nowMillis - previousGapReportAt >= periodicIntervalMillis
            producerGapActive = true
            if (!enteringGap && !periodicRefresh) return

            lastProducerGapReportedAtMillis = nowMillis
            runCatching {
                observer(PRODUCER_GAP_STATUS, formatProducerGapMessage("active", snapshot))
            }
            return
        }

        if (!producerGapActive) return
        producerGapActive = false
        lastProducerGapReportedAtMillis = null
        runCatching {
            observer(PRODUCER_GAP_STATUS, formatProducerGapMessage("resolved", snapshot))
        }
    }

    private fun formatProducerGapMessage(
        state: String,
        snapshot: AceLivePeerProductionSnapshot
    ): String = buildString {
        append("state=")
        append(state)
        append(" discovered=")
        append(snapshot.discoveredCandidates)
        append(" connected=")
        append(snapshot.connectedPeers)
        append(" handshaked=")
        append(snapshot.handshakedPeers)
        append(" windowUseful=")
        append(snapshot.windowUsefulPeers)
        append(" unchoked=")
        append(snapshot.unchokedPeers)
        append(" producing=")
        append(snapshot.producingPeers)
        append(" aggregate_bps=")
        append(snapshot.aggregateBytesPerSecond.coerceAtLeast(0L))
        appendRuntimeContext()
    }

    private fun StringBuilder.appendRuntimeContext() {
        context?.let { correlation ->
            append(" startup_id=")
            append(correlation.startupId)
            append(" runtime_id=")
            append(correlation.runtimeId)
            append(" generation=")
            append(correlation.generation)
            append(" path=")
            append(correlation.path)
        }
    }

    private data class LifecycleSignature(
        val discovered: Int,
        val connected: Int,
        val handshaked: Int
    ) {
        companion object {
            fun from(snapshot: AceLivePeerProductionSnapshot) = LifecycleSignature(
                discovered = snapshot.discoveredCandidates,
                connected = snapshot.connectedPeers,
                handshaked = snapshot.handshakedPeers
            )
        }
    }

    private companion object {
        const val QUALITY_STATUS = "embedded_ace_live_peer_quality"
        const val PRODUCER_GAP_STATUS = "embedded_ace_live_producer_gap"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
        const val BITS_PER_BYTE = 8.0
        const val BITS_PER_MEGABIT = 1_000_000.0
    }
}
