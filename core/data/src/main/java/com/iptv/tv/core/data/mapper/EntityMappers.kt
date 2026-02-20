package com.iptv.tv.core.data.mapper

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.SyncLog

fun PlaylistEntity.toModel(channelCount: Int = 0): Playlist {
    return Playlist(
        id = id,
        name = name,
        sourceType = PlaylistSourceType.valueOf(sourceType),
        source = source,
        epgSourceUrl = epgSourceUrl,
        scheduleHours = scheduleHours,
        lastSyncedAt = lastSyncedAt,
        channelCount = channelCount,
        isCustom = isCustom
    )
}

fun ChannelEntity.toModel(): Channel {
    return Channel(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        group = groupName,
        logo = logo,
        streamUrl = streamUrl,
        health = ChannelHealth.valueOf(health),
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}

fun Channel.toEntity(): ChannelEntity {
    return ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        groupName = group,
        logo = logo,
        streamUrl = streamUrl,
        health = health.name,
        orderIndex = orderIndex,
        isHidden = isHidden
    )
}

fun HistoryEntity.toModel(): PlaybackHistoryItem {
    return PlaybackHistoryItem(
        id = id,
        channelId = channelId,
        channelName = channelName,
        playedAt = playedAt
    )
}

fun SyncLogEntity.toModel(): SyncLog {
    return SyncLog(
        id = id,
        playlistId = playlistId,
        status = status,
        message = message,
        createdAt = createdAt
    )
}

fun DownloadEntity.toModel(): DownloadTask {
    return DownloadTask(
        id = id,
        source = source,
        progress = progress,
        status = runCatching { DownloadStatus.valueOf(status) }.getOrDefault(DownloadStatus.QUEUED),
        createdAt = createdAt
    )
}
