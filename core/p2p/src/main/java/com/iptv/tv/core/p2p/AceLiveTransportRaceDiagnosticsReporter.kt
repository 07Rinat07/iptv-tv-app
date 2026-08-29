package com.iptv.tv.core.p2p

internal class AceLiveTransportRaceDiagnosticsReporter(
    private val observer: (status: String, message: String) -> Unit,
    private val context: AceLiveRuntimeDiagnosticsContext,
    private val delegate: P2pRuntimeMetricsReporter = P2pRuntimeMetricsReporter.LOGCAT
) : P2pRuntimeMetricsReporter {
    override fun report(metric: P2pRuntimeMetric) {
        delegate.reportSafely(metric)
        val race = metric as? AceLiveTransportRaceMetric ?: return
        runCatching {
            observer(STATUS, race.toDiagnosticsMessage(context))
        }
    }

    private companion object {
        const val STATUS = "embedded_ace_live_transport_race"
    }
}

internal fun AceLiveTransportRaceMetric.toDiagnosticsMessage(
    context: AceLiveRuntimeDiagnosticsContext
): String = buildString {
    append("winner=")
    append(winner?.wireName ?: "none")
    append(" elapsed_ms=")
    append(elapsedMillis)
    candidates
        .sortedBy { candidate -> candidate.transport.ordinal }
        .forEach { candidate ->
            append(' ')
            append(candidate.transport.wireName)
            append("_connected_ms=")
            append(candidate.physicalConnectedMillis ?: "none")
            append(' ')
            append(candidate.transport.wireName)
            append("_outcome=")
            append(candidate.outcome.wireName)
            append(' ')
            append(candidate.transport.wireName)
            append("_terminal_ms=")
            append(candidate.terminalElapsedMillis)
        }
    append(" startup_id=")
    append(context.startupId)
    append(" runtime_id=")
    append(context.runtimeId)
    append(" generation=")
    append(context.generation)
    append(" path=")
    append(context.path)
}
