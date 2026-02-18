package com.iptv.tv.core.model

enum class PlaylistSourceType {
    URL,
    TEXT,
    FILE,
    GITHUB,
    GITLAB,
    BITBUCKET,
    CUSTOM
}

enum class ChannelHealth {
    UNKNOWN,
    AVAILABLE,
    UNSTABLE,
    UNAVAILABLE
}

enum class PlayerType {
    INTERNAL,
    VLC
}

enum class BufferProfile {
    MINIMAL,
    STANDARD,
    HIGH,
    MANUAL
}

data class ManualBufferSettings(
    val startMs: Int,
    val rebufferMs: Int,
    val maxMs: Int
)

data class Playlist(
    val id: Long,
    val name: String,
    val sourceType: PlaylistSourceType,
    val source: String,
    val scheduleHours: Int,
    val lastSyncedAt: Long?,
    val channelCount: Int,
    val isCustom: Boolean
)

data class Channel(
    val id: Long,
    val playlistId: Long,
    val tvgId: String?,
    val name: String,
    val group: String?,
    val logo: String?,
    val streamUrl: String,
    val health: ChannelHealth,
    val orderIndex: Int,
    val isHidden: Boolean
)

data class PlaylistCandidate(
    val id: String,
    val provider: String,
    val repository: String,
    val path: String,
    val name: String,
    val downloadUrl: String,
    val updatedAt: String,
    val sizeBytes: Long?
)

enum class ScannerProviderScope {
    ALL,
    GITHUB,
    GITLAB,
    BITBUCKET
}

data class ScannerSearchRequest(
    val query: String,
    val keywords: List<String> = emptyList(),
    val providerScope: ScannerProviderScope = ScannerProviderScope.ALL,
    val repoFilter: String? = null,
    val pathFilter: String? = null,
    val updatedAfterEpochMs: Long? = null,
    val minSizeBytes: Long? = null,
    val maxSizeBytes: Long? = null,
    val limit: Int = 50
)

data class SyncLog(
    val id: Long,
    val playlistId: Long?,
    val status: String,
    val message: String,
    val createdAt: Long
)

enum class DownloadStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELED
}

data class DownloadTask(
    val id: Long,
    val source: String,
    val progress: Int,
    val status: DownloadStatus,
    val createdAt: Long
)

data class PlaybackHistoryItem(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    val playedAt: Long
)

data class EngineStatus(
    val connected: Boolean,
    val peers: Int,
    val speedKbps: Int,
    val message: String
)

data class PlaylistImportReport(
    val playlistId: Long,
    val totalParsed: Int,
    val totalImported: Int,
    val removedDuplicates: Int,
    val warnings: List<String>,
    val autoChecked: Int,
    val available: Int,
    val unstable: Int,
    val unavailable: Int
)

data class PlaylistValidationReport(
    val playlistId: Long,
    val totalChecked: Int,
    val available: Int,
    val unstable: Int,
    val unavailable: Int
)

data class EditorActionResult(
    val effectivePlaylistId: Long,
    val affectedCount: Int,
    val createdWorkingCopy: Boolean,
    val message: String
)

data class EditorExportResult(
    val playlistId: Long,
    val channelCount: Int,
    val m3uContent: String
)
