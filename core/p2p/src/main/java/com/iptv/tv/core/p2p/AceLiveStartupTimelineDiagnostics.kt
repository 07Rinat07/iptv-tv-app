package com.iptv.tv.core.p2p

/**
 * Observational bridge from runtime boundaries to the canonical Ace Live startup timeline.
 *
 * The bridge deliberately owns no scheduler, retry, timeout or buffer decisions. Callers report an
 * already-observed boundary and the bridge emits exactly one stable diagnostic record for the first
 * occurrence of that milestone.
 *
 * Runtime adapters below keep the event-to-milestone mapping in one place. They intentionally do
 * not infer success from discovery alone: discovery completion, a candidate, TCP connection, an
 * accepted handshake and a useful live window remain separate evidence stages.
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

    fun onTransportSelection(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.TRANSPORT_SELECTION, atMillis)

    fun onDirectAttempt(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.DIRECT_ATTEMPT, atMillis)

    fun onMetadataAttempt(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.METADATA_ATTEMPT, atMillis)

    fun onDiscoveryCompleted(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.DISCOVERY_COMPLETED, atMillis)

    fun onDiscoveryCandidates(
        candidateCount: Int,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? =
        if (candidateCount > 0) {
            mark(AceLiveStartupMilestone.FIRST_CANDIDATE, atMillis)
        } else {
            null
        }

    fun onPoolEvent(
        event: AceLiveTcpPoolEvent,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? = when (event) {
        is AceLiveTcpPoolEvent.TransportConnected ->
            mark(AceLiveStartupMilestone.TRANSPORT_CONNECTED, atMillis)
        is AceLiveTcpPoolEvent.HandshakeAccepted ->
            mark(AceLiveStartupMilestone.HANDSHAKE_ACCEPTED, atMillis)
        is AceLiveTcpPoolEvent.HandshakeRejected,
        is AceLiveTcpPoolEvent.ConnectFailed,
        is AceLiveTcpPoolEvent.Disconnected,
        is AceLiveTcpPoolEvent.Ingress -> null
    }

    fun onPeerQuality(
        peer: AceLivePeerQualitySnapshot,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? =
        if (peer.windowUseful) {
            mark(AceLiveStartupMilestone.USEFUL_WINDOW, atMillis)
        } else {
            null
        }

    fun onPeerProduction(
        snapshot: AceLivePeerProductionSnapshot,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? =
        if (snapshot.windowUsefulPeers > 0) {
            mark(AceLiveStartupMilestone.USEFUL_WINDOW, atMillis)
        } else {
            null
        }

    fun onFirstMedia(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.FIRST_MEDIA, atMillis)

    fun onBufferReady(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.BUFFER_READY, atMillis)

    fun onConsumerLifecycle(
        event: AceLiveConsumerLifecycleEvent,
        atMillis: Long = clockMillis()
    ): AceLiveStartupTimelineEntry? = when (event) {
        is AceLiveConsumerLifecycleEvent.Opened ->
            mark(AceLiveStartupMilestone.HTTP_READER_OPEN, atMillis)
        is AceLiveConsumerLifecycleEvent.Delivered,
        is AceLiveConsumerLifecycleEvent.Closed -> null
    }

    fun onFirstRead(atMillis: Long = clockMillis()) =
        mark(AceLiveStartupMilestone.HTTP_FIRST_READ, atMillis)

    fun snapshot(): List<AceLiveStartupTimelineEntry> = timeline.snapshot()

    companion object {
        const val STATUS = "embedded_ace_live_startup_timeline"
    }
}
