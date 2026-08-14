package com.iptv.tv.core.p2p

import java.util.Locale

/**
 * Converts the immutable peer-production snapshot into a bounded persistent diagnostics stream.
 *
 * Stage-count changes are material and are emitted immediately. Delivery rate and media freshness
 * naturally drift while the stage counts stay stable, so those values are refreshed periodically
 * instead of writing a database row on every 200 ms scheduler tick or every media chunk.
 */
internal class AceLivePeerDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val periodicIntervalMillis: Long = DEFAULT_PERIODIC_INTERVAL_MILLIS
) {
    private var lastSignature: StageSignature? = null
    private var lastReportedAtMillis: Long? = null

    init {
        require(periodicIntervalMillis > 0L) { "periodicIntervalMillis must be positive" }
    }

    fun maybeReport(snapshot: AceLivePeerProductionSnapshot, nowMillis: Long) {
        val now = nowMillis.coerceAtLeast(0L)
        val signature = StageSignature.from(snapshot)
        val previousSignature = lastSignature
        val previousReportAt = lastReportedAtMillis
        val materialStageChange = previousSignature == null || previousSignature != signature
        val periodicRefresh = previousReportAt == null || now - previousReportAt >= periodicIntervalMillis
        if (!materialStageChange && !periodicRefresh) return

        lastSignature = signature
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

    private data class StageSignature(
        val discovered: Int,
        val connected: Int,
        val handshaked: Int,
        val windowUseful: Int,
        val unchoked: Int,
        val producing: Int
    ) {
        companion object {
            fun from(snapshot: AceLivePeerProductionSnapshot) = StageSignature(
                discovered = snapshot.discoveredCandidates,
                connected = snapshot.connectedPeers,
                handshaked = snapshot.handshakedPeers,
                windowUseful = snapshot.windowUsefulPeers,
                unchoked = snapshot.unchokedPeers,
                producing = snapshot.producingPeers
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
