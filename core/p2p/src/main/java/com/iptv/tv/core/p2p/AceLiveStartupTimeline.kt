package com.iptv.tv.core.p2p

import java.util.concurrent.ConcurrentHashMap

/**
 * One monotonic startup timeline shared by Ace Live transport, peer, loopback and player boundaries.
 *
 * The collector is deliberately observational: it never changes scheduling, retries, timeouts or
 * consumer ownership. Each milestone is first-write-wins so retries/reopens cannot rewrite the
 * original startup evidence used for same-device latency comparisons.
 */
internal class AceLiveStartupTimeline(
    private val startedAtMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis
) {
    private val milestones = ConcurrentHashMap<AceLiveStartupMilestone, Long>()

    init {
        require(startedAtMillis >= 0L) { "startup timestamp must be non-negative" }
    }

    fun mark(
        milestone: AceLiveStartupMilestone,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry {
        val normalizedAt = atMillis.coerceAtLeast(startedAtMillis)
        val recordedAt = milestones.putIfAbsent(milestone, normalizedAt) ?: normalizedAt
        return entry(milestone, recordedAt)
    }

    /**
     * Records [milestone] only when this call wins the first-write race.
     *
     * This is intended for diagnostic emitters that must not duplicate a canonical startup phase
     * when peer reconnects, HTTP reader reopens or Media3 retries repeat the same boundary.
     */
    fun markIfFirst(
        milestone: AceLiveStartupMilestone,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? {
        val normalizedAt = atMillis.coerceAtLeast(startedAtMillis)
        val previous = milestones.putIfAbsent(milestone, normalizedAt)
        return if (previous == null) entry(milestone, normalizedAt) else null
    }

    fun entry(milestone: AceLiveStartupMilestone): AceLiveStartupTimelineEntry? =
        milestones[milestone]?.let { recordedAt -> entry(milestone, recordedAt) }

    fun snapshot(): List<AceLiveStartupTimelineEntry> =
        AceLiveStartupMilestone.entries.mapNotNull(::entry)

    fun diagnosticLine(entry: AceLiveStartupTimelineEntry): String =
        "phase=${entry.milestone.wireName}, elapsed_ms=${entry.elapsedMillis}"

    private fun entry(
        milestone: AceLiveStartupMilestone,
        recordedAtMillis: Long
    ): AceLiveStartupTimelineEntry = AceLiveStartupTimelineEntry(
        milestone = milestone,
        elapsedMillis = (recordedAtMillis - startedAtMillis).coerceAtLeast(0L)
    )
}

internal data class AceLiveStartupTimelineEntry(
    val milestone: AceLiveStartupMilestone,
    val elapsedMillis: Long
)

/** Ordered field-validation contract from transport selection to visible playback. */
internal enum class AceLiveStartupMilestone(val wireName: String) {
    TRANSPORT_SELECTION("transport_selection"),
    DIRECT_ATTEMPT("direct_attempt"),
    METADATA_ATTEMPT("metadata_attempt"),
    FIRST_CANDIDATE("first_candidate"),
    TRANSPORT_CONNECTED("connected"),
    HANDSHAKE_ACCEPTED("handshake"),
    USEFUL_WINDOW("useful_window"),
    FIRST_MEDIA("first_media"),
    BUFFER_READY("buffer_ready"),
    HTTP_READER_OPEN("http_reader_open"),
    HTTP_FIRST_READ("http_first_read"),
    MEDIA3_READY("media3_ready"),
    FIRST_FRAME("first_frame")
}
