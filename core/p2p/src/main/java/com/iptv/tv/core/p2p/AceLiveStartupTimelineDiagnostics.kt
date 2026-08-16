package com.iptv.tv.core.p2p

/**
 * Observational bridge from runtime boundaries to the canonical Ace Live startup timeline.
 *
 * The bridge deliberately owns no scheduler, retry, timeout or buffer decisions. Callers report an
 * already-observed boundary and the bridge emits exactly one stable diagnostic record for the first
 * occurrence of that milestone.
 */
internal class AceLiveStartupTimelineDiagnostics(
    startedAtMillis: Long,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val diagnosticsObserver: (status: String, message: String) -> Unit
) {
    private val timeline = AceLiveStartupTimeline(
        startedAtMillis = startedAtMillis,
        clockMillis = clockMillis
    )

    fun mark(
        milestone: AceLiveStartupMilestone,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? {
        val entry = timeline.markIfFirst(milestone, atMillis) ?: return null
        runCatching {
            diagnosticsObserver(STATUS, timeline.diagnosticLine(entry))
        }
        return entry
    }

    fun snapshot(): List<AceLiveStartupTimelineEntry> = timeline.snapshot()

    companion object {
        const val STATUS = "embedded_ace_live_startup_timeline"
    }
}
