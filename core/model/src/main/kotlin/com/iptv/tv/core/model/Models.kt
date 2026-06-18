package com.iptv.tv.core.model

enum class PlaylistSourceType {
    URL,
    TEXT,
    FILE,
    GITHUB,
    GITLAB,
    BITBUCKET,
    XTREAM,
    STALKER,
    JELLYFIN,
    PLEX,
    TVHEADEND,
    HDHOMERUN,
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

data class ScannerProxySettings(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int? = null,
    val username: String = "",
    val password: String = ""
)

data class ScannerLearnedQuery(
    val query: String,
    val hits: Int,
    val lastSuccessAt: Long,
    val presetId: String? = null
)

data class Playlist(
    val id: Long,
    val name: String,
    val sourceType: PlaylistSourceType,
    val source: String,
    val epgSourceUrl: String? = null,
    val scheduleHours: Int,
    val lastSyncedAt: Long?,
    val channelCount: Int,
    val isCustom: Boolean
)

data class ChannelPreview(
    val id: Long,
    val name: String,
    val group: String?,
    val logo: String?,
    val health: ChannelHealth,
    val isHidden: Boolean
)

data class PlaylistContentSummary(
    val playlistId: Long,
    val playlistName: String,
    val sourceType: PlaylistSourceType,
    val source: String,
    val epgSourceUrl: String?,
    val totalChannels: Int,
    val visibleChannels: Int,
    val hiddenChannels: Int,
    val channelsWithLogo: Int,
    val channelsWithTvgId: Int,
    val availableChannels: Int,
    val unstableChannels: Int,
    val unavailableChannels: Int,
    val unknownHealthChannels: Int,
    val groupCount: Int,
    val topGroups: List<Pair<String, Int>>,
    val channelPreviews: List<ChannelPreview>
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

data class EpgProgram(
    val title: String,
    val description: String?,
    val category: String?,
    val startEpochMs: Long,
    val endEpochMs: Long
)

data class ChannelEpgInfo(
    val channelId: Long,
    val channelName: String,
    val tvgId: String?,
    val epgSourceUrl: String?,
    val matchedBy: String,
    val now: EpgProgram?,
    val next: EpgProgram?,
    val upcoming: List<EpgProgram>
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

enum class ScannerSearchMode {
    AUTO,
    DIRECT_API,
    SEARCH_ENGINE
}

data class ScannerSearchRequest(
    val query: String,
    val keywords: List<String> = emptyList(),
    val providerScope: ScannerProviderScope = ScannerProviderScope.ALL,
    val searchMode: ScannerSearchMode = ScannerSearchMode.AUTO,
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

enum class DownloadSourceType {
    HTTP_STREAM,
    HLS_PLAYLIST,
    TORRENT_FILE,
    MAGNET,
    ACESTREAM,
    LOCAL_FILE,
    CUSTOM
}

enum class RecordingStatus {
    SCHEDULED,
    RECORDING,
    COMPLETED,
    FAILED,
    CANCELED
}

enum class RecordingRepeatMode {
    ONCE,
    DAILY,
    WEEKLY,
    SERIES
}

enum class RecordingStorageLocation {
    INTERNAL,
    APP_EXTERNAL,
    CUSTOM_EXTERNAL
}

data class RecordingStorageInfo(
    val location: RecordingStorageLocation,
    val path: String,
    val exists: Boolean,
    val writable: Boolean,
    val freeBytes: Long,
    val usingFallback: Boolean,
    val configured: Boolean = true
)

data class TimeshiftBufferPlan(
    val channelId: Long,
    val channelName: String,
    val requestedDurationMinutes: Int,
    val maxDurationMinutes: Int,
    val estimatedBytes: Long,
    val availableBytes: Long,
    val storagePath: String,
    val storageLocation: RecordingStorageLocation,
    val sourceType: DownloadSourceType,
    val supported: Boolean,
    val reason: String?
)

enum class ProviderAuthType {
    NONE,
    USER_PASSWORD,
    TOKEN,
    MAC_ADDRESS
}

enum class ProviderType {
    M3U,
    XTREAM,
    STALKER,
    JELLYFIN,
    PLEX,
    TVHEADEND,
    HDHOMERUN
}

enum class TvHomeChannelType {
    RECENT_CHANNELS,
    FAVORITES,
    WATCH_NEXT,
    RECORDINGS
}

data class DownloadTask(
    val id: Long,
    val source: String,
    val progress: Int,
    val status: DownloadStatus,
    val createdAt: Long,
    val sourceType: DownloadSourceType = DownloadSourceType.CUSTOM
)

data class RecordingTask(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    val programTitle: String?,
    val streamUrl: String,
    val filePath: String?,
    val status: RecordingStatus,
    val startedAt: Long?,
    val endedAt: Long?,
    val scheduledStartAt: Long?,
    val scheduledEndAt: Long?,
    val createdAt: Long
)

data class RecordingSchedule(
    val id: Long,
    val channelId: Long,
    val channelName: String,
    val programTitle: String?,
    val startAt: Long,
    val endAt: Long,
    val repeatMode: RecordingRepeatMode,
    val enabled: Boolean,
    val createdAt: Long
)

data class PlaylistProvider(
    val id: Long,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val username: String?,
    val password: String?,
    val token: String?,
    val macAddress: String?,
    val authType: ProviderAuthType,
    val linkedPlaylistId: Long?,
    val lastSyncedAt: Long?,
    val createdAt: Long
)

data class ProviderAccountStatus(
    val providerId: Long,
    val type: ProviderType,
    val ok: Boolean,
    val statusText: String,
    val detail: String?,
    val checkedAt: Long,
    val diagnosticKind: ProviderDiagnosticKind = if (ok) ProviderDiagnosticKind.OK else ProviderDiagnosticKind.PROVIDER_ERROR,
    val hint: String? = null,
    val testedUrl: String? = null
)

data class ProviderSyncHistory(
    val id: Long,
    val providerId: Long,
    val providerName: String,
    val providerType: ProviderType,
    val status: String,
    val playlistId: Long?,
    val reason: ProviderDiagnosticKind?,
    val detail: String?,
    val createdAt: Long
)

enum class ProviderDiagnosticKind {
    OK,
    AUTH,
    NETWORK,
    PARSER,
    EMPTY_PLAYLIST,
    UNSUPPORTED,
    PROVIDER_ERROR
}

data class ParentalControlProfile(
    val id: Long,
    val name: String,
    val pinHash: String,
    val blockedKeywords: List<String>,
    val lockedSettings: Boolean,
    val enabled: Boolean,
    val createdAt: Long
)

data class ParentalControlSettings(
    val enabled: Boolean,
    val pinConfigured: Boolean,
    val hideAdultChannels: Boolean,
    val blockedKeywords: List<String>
)

data class ChannelMetadata(
    val channelId: Long,
    val normalizedName: String?,
    val country: String?,
    val language: String?,
    val category: String?,
    val resolvedLogoUrl: String?,
    val manualLogoUrl: String?,
    val metadataSource: String?,
    val updatedAt: Long,
    val manualCountry: String? = null,
    val manualLanguage: String? = null,
    val manualCategory: String? = null
)

data class TvHomeChannelState(
    val type: TvHomeChannelType,
    val providerChannelId: Long?,
    val enabled: Boolean,
    val lastPublishedAt: Long?
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
