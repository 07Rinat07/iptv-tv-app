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
