package com.iptv.tv.core.p2p

import android.util.Log

sealed interface P2pRuntimeMetric {
    val sourceType: String
    val elapsedMillis: Long

    data class MetadataReady(
        override val sourceType: String,
        override val elapsedMillis: Long,
        val fileCount: Int,
        val pieceLengthBytes: Int
    ) : P2pRuntimeMetric

    data class StreamReady(
        override val sourceType: String,
        override val elapsedMillis: Long,
        val metadataMillis: Long,
        val fileName: String,
        val fileSizeBytes: Long,
        val downloadRateBytesPerSecond: Long,
        val dhtNodes: Long
    ) : P2pRuntimeMetric

    data class FirstByteReady(
        override val sourceType: String,
        override val elapsedMillis: Long,
        val positionBytes: Long,
        val byteCount: Int
    ) : P2pRuntimeMetric
}

fun interface P2pRuntimeMetricsReporter {
    fun report(metric: P2pRuntimeMetric)

    companion object {
        val LOGCAT = P2pRuntimeMetricsReporter { metric ->
            Log.i(LOG_TAG, metric.toLogLine())
        }

        val NONE = P2pRuntimeMetricsReporter { }
    }
}

internal fun P2pRuntimeMetricsReporter.reportSafely(metric: P2pRuntimeMetric) {
    runCatching { report(metric) }
}

internal fun P2pRuntimeMetric.toLogLine(): String = when (this) {
    is P2pRuntimeMetric.MetadataReady ->
        "event=metadata_ready source=$sourceType elapsed_ms=$elapsedMillis " +
            "files=$fileCount piece_length_bytes=$pieceLengthBytes"

    is P2pRuntimeMetric.StreamReady ->
        "event=stream_ready source=$sourceType elapsed_ms=$elapsedMillis metadata_ms=$metadataMillis " +
            "file_size_bytes=$fileSizeBytes download_bps=$downloadRateBytesPerSecond dht_nodes=$dhtNodes " +
            "file=${fileName.replace(' ', '_')}"

    is P2pRuntimeMetric.FirstByteReady ->
        "event=first_byte_ready source=$sourceType elapsed_ms=$elapsedMillis " +
            "position_bytes=$positionBytes bytes=$byteCount"

    is AceLiveTransportRaceMetric -> buildString {
        append("event=ace_live_transport_race source=")
        append(sourceType)
        append(" elapsed_ms=")
        append(elapsedMillis)
        append(" endpoint_host=")
        append(endpointHost.replace(' ', '_'))
        append(" endpoint_port=")
        append(endpointPort)
        append(" winner=")
        append(winner?.wireName ?: "none")
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
    }
}

internal fun elapsedMillis(startNanos: Long, endNanos: Long): Long {
    require(endNanos >= startNanos) { "endNanos must not precede startNanos" }
    return (endNanos - startNanos) / NANOS_PER_MILLI
}

internal fun P2pSource.metricSourceType(): String = when (this) {
    is P2pSource.Magnet -> "magnet"
    is P2pSource.InfoHash -> "infohash"
    is P2pSource.TorrentUrl -> "torrent_url"
    is P2pSource.LocalTorrentUri -> "local_torrent"
    is P2pSource.AceContentId -> "ace_content_id"
}

private const val LOG_TAG = "P2P/Metrics"
private const val NANOS_PER_MILLI = 1_000_000L
