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
}
