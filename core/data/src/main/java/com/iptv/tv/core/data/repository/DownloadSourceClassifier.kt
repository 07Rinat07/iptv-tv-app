package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.DownloadSourceType

internal object DownloadSourceClassifier {
    fun classify(source: String): DownloadSourceType {
        val trimmed = source.trim()
        val lower = trimmed.lowercase()
        return when {
            lower.startsWith("magnet:?") -> DownloadSourceType.MAGNET
            lower.startsWith("acestream://") || lower.startsWith("ace://") -> DownloadSourceType.ACESTREAM
            lower.startsWith("file://") || trimmed.startsWith("/") -> DownloadSourceType.LOCAL_FILE
            lower.substringBefore('?').substringBefore('#').endsWith(".m3u8") -> DownloadSourceType.HLS_PLAYLIST
            lower.substringBefore('?').substringBefore('#').endsWith(".torrent") -> DownloadSourceType.TORRENT_FILE
            lower.startsWith("http://") || lower.startsWith("https://") -> DownloadSourceType.HTTP_STREAM
            else -> DownloadSourceType.CUSTOM
        }
    }

    fun requiresExternalEngine(sourceType: DownloadSourceType): Boolean {
        return sourceType == DownloadSourceType.MAGNET ||
            sourceType == DownloadSourceType.ACESTREAM ||
            sourceType == DownloadSourceType.TORRENT_FILE
    }

    fun engineFailureLogStatus(message: String): String {
        val lower = message.lowercase()
        return when {
            TRACKER_FAILURE_MARKERS.any { lower.contains(it) } -> "download_tracker_error"
            PEER_FAILURE_MARKERS.any { lower.contains(it) } -> "download_peer_error"
            else -> "download_engine_error"
        }
    }

    private val TRACKER_FAILURE_MARKERS = listOf(
        "tracker",
        "announce",
        "scrape"
    )

    private val PEER_FAILURE_MARKERS = listOf(
        "peer",
        "peers",
        "seed",
        "seeder",
        "leech",
        "swarm"
    )
}
