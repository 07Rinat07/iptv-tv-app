package com.iptv.tv.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "playlists",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["lastSyncedAt"]),
        Index(value = ["sourceType"])
    ]
)
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sourceType: String,
    val source: String,
    val epgSourceUrl: String?,
    val scheduleHours: Int,
    val lastSyncedAt: Long?,
    val isCustom: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "channels",
    indices = [
        Index(value = ["playlistId", "orderIndex"]),
        Index(value = ["playlistId", "isHidden"]),
        Index(value = ["playlistId", "health"]),
        Index(value = ["tvgId"])
    ]
)
data class ChannelEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val tvgId: String?,
    val name: String,
    val groupName: String?,
    val logo: String?,
    val streamUrl: String,
    val health: String,
    val orderIndex: Int,
    val isHidden: Boolean
)

@Entity(
    tableName = "favorites",
    indices = [
        Index(value = ["addedAt"])
    ]
)
data class FavoriteEntity(
    @PrimaryKey val channelId: Long,
    val addedAt: Long
)

@Entity(
    tableName = "history",
    indices = [
        Index(value = ["playedAt"]),
        Index(value = ["channelId"])
    ]
)
data class HistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val playedAt: Long
)

@Entity(
    tableName = "sync_logs",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["playlistId"]),
        Index(value = ["status"])
    ]
)
data class SyncLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long?,
    val status: String,
    val message: String,
    val createdAt: Long
)

@Entity(
    tableName = "downloads",
    indices = [
        Index(value = ["createdAt"]),
        Index(value = ["status"])
    ]
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,
    val progress: Int,
    val status: String,
    val createdAt: Long
)

@Entity(
    tableName = "recordings",
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["status"]),
        Index(value = ["scheduledStartAt"]),
        Index(value = ["createdAt"])
    ]
)
data class RecordingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val programTitle: String?,
    val streamUrl: String,
    val filePath: String?,
    val status: String,
    val startedAt: Long?,
    val endedAt: Long?,
    val scheduledStartAt: Long?,
    val scheduledEndAt: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "recording_schedules",
    indices = [
        Index(value = ["channelId"]),
        Index(value = ["startAt"]),
        Index(value = ["enabled"])
    ]
)
data class RecordingScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val channelId: Long,
    val channelName: String,
    val programTitle: String?,
    val startAt: Long,
    val endAt: Long,
    val repeatMode: String,
    val enabled: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "playlist_providers",
    indices = [
        Index(value = ["type"]),
        Index(value = ["baseUrl"]),
        Index(value = ["linkedPlaylistId"])
    ]
)
data class PlaylistProviderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val name: String,
    val baseUrl: String,
    val username: String?,
    val password: String?,
    val token: String?,
    val macAddress: String?,
    val authType: String,
    val linkedPlaylistId: Long?,
    val lastSyncedAt: Long?,
    val createdAt: Long
)

@Entity(
    tableName = "provider_sync_history",
    indices = [
        Index(value = ["providerId", "createdAt"]),
        Index(value = ["providerType"]),
        Index(value = ["status"]),
        Index(value = ["reason"])
    ]
)
data class ProviderSyncHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val providerName: String,
    val providerType: String,
    val status: String,
    val playlistId: Long?,
    val reason: String?,
    val detail: String?,
    val createdAt: Long
)

@Entity(
    tableName = "parental_profiles",
    indices = [
        Index(value = ["enabled"])
    ]
)
data class ParentalProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val pinHash: String,
    val blockedKeywordsCsv: String,
    val lockedSettings: Boolean,
    val enabled: Boolean,
    val createdAt: Long
)

@Entity(
    tableName = "channel_metadata",
    indices = [
        Index(value = ["country"]),
        Index(value = ["language"]),
        Index(value = ["category"])
    ]
)
data class ChannelMetadataEntity(
    @PrimaryKey val channelId: Long,
    val normalizedName: String?,
    val country: String?,
    val language: String?,
    val category: String?,
    val resolvedLogoUrl: String?,
    val manualLogoUrl: String?,
    val metadataSource: String?,
    val updatedAt: Long
)

@Entity(
    tableName = "tv_home_channels",
    indices = [
        Index(value = ["enabled"]),
        Index(value = ["lastPublishedAt"])
    ]
)
data class TvHomeChannelEntity(
    @PrimaryKey val type: String,
    val providerChannelId: Long?,
    val enabled: Boolean,
    val lastPublishedAt: Long?
)
