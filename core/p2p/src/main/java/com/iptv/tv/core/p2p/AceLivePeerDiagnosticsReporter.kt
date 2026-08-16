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
 */
internal class AceLivePeerDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS
) {
    private var lastLifecycleSignature: LifecycleSignature? = null
    private var lastReportedAtMillis: Long? = null

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
        if (!materialLifecycleChange && !periodicRefresh) return

        lastLifecycleSignature = lifecycleSignature
        lastReportedAtMillis = now
        runCatching {
            observer(STATUS, formatMessage(snapshot))
        }
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
        const val STATUS = "embedded_ace_live_peer_quality"
        const val DEFAULT_PERIODIC_INTERVAL_MILLIS = 5_000L
        const val BITS_PER_BYTE = 8.0
        const val BITS_PER_MEGABIT = 1_000_000.0
    }
}
