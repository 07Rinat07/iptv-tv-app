package com.iptv.tv.core.data.mapper

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.ChannelMetadataEntity
import com.iptv.tv.core.database.entity.DownloadEntity
import com.iptv.tv.core.database.entity.HistoryEntity
import com.iptv.tv.core.database.entity.ParentalProfileEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.database.entity.PlaylistProviderEntity
import com.iptv.tv.core.database.entity.RecordingEntity
import com.iptv.tv.core.database.entity.RecordingScheduleEntity
import com.iptv.tv.core.database.entity.SyncLogEntity
import com.iptv.tv.core.database.entity.TvHomeChannelEntity
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelMetadata
import com.iptv.tv.core.model.DownloadStatus
import com.iptv.tv.core.model.DownloadTask
import com.iptv.tv.core.model.ParentalControlProfile
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistProvider
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.ProviderAuthType
import com.iptv.tv.core.model.ProviderType
import com.iptv.tv.core.model.RecordingRepeatMode
import com.iptv.tv.core.model.RecordingSchedule
import com.iptv.tv.core.model.RecordingStatus
import com.iptv.tv.core.model.RecordingTask
import com.iptv.tv.core.model.SyncLog
import com.iptv.tv.core.model.TvHomeChannelState
import com.iptv.tv.core.model.TvHomeChannelType

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

fun RecordingEntity.toModel(): RecordingTask {
    return RecordingTask(
        id = id,
        channelId = channelId,
        channelName = channelName,
        programTitle = programTitle,
        streamUrl = streamUrl,
        filePath = filePath,
        status = runCatching { RecordingStatus.valueOf(status) }.getOrDefault(RecordingStatus.SCHEDULED),
        startedAt = startedAt,
        endedAt = endedAt,
        scheduledStartAt = scheduledStartAt,
        scheduledEndAt = scheduledEndAt,
        createdAt = createdAt
    )
}

fun RecordingTask.toEntity(): RecordingEntity {
    return RecordingEntity(
        id = id,
        channelId = channelId,
        channelName = channelName,
        programTitle = programTitle,
        streamUrl = streamUrl,
        filePath = filePath,
        status = status.name,
        startedAt = startedAt,
        endedAt = endedAt,
        scheduledStartAt = scheduledStartAt,
        scheduledEndAt = scheduledEndAt,
        createdAt = createdAt
    )
}

fun RecordingScheduleEntity.toModel(): RecordingSchedule {
    return RecordingSchedule(
        id = id,
        channelId = channelId,
        channelName = channelName,
        programTitle = programTitle,
        startAt = startAt,
        endAt = endAt,
        repeatMode = runCatching { RecordingRepeatMode.valueOf(repeatMode) }.getOrDefault(RecordingRepeatMode.ONCE),
        enabled = enabled,
        createdAt = createdAt
    )
}

fun RecordingSchedule.toEntity(): RecordingScheduleEntity {
    return RecordingScheduleEntity(
        id = id,
        channelId = channelId,
        channelName = channelName,
        programTitle = programTitle,
        startAt = startAt,
        endAt = endAt,
        repeatMode = repeatMode.name,
        enabled = enabled,
        createdAt = createdAt
    )
}

fun PlaylistProviderEntity.toModel(): PlaylistProvider {
    return PlaylistProvider(
        id = id,
        type = runCatching { ProviderType.valueOf(type) }.getOrDefault(ProviderType.M3U),
        name = name,
        baseUrl = baseUrl,
        username = username,
        password = password,
        token = token,
        macAddress = macAddress,
        authType = runCatching { ProviderAuthType.valueOf(authType) }.getOrDefault(ProviderAuthType.NONE),
        linkedPlaylistId = linkedPlaylistId,
        lastSyncedAt = lastSyncedAt,
        createdAt = createdAt
    )
}

fun PlaylistProvider.toEntity(): PlaylistProviderEntity {
    return PlaylistProviderEntity(
        id = id,
        type = type.name,
        name = name,
        baseUrl = baseUrl,
        username = username,
        password = password,
        token = token,
        macAddress = macAddress,
        authType = authType.name,
        linkedPlaylistId = linkedPlaylistId,
        lastSyncedAt = lastSyncedAt,
        createdAt = createdAt
    )
}

fun ParentalProfileEntity.toModel(): ParentalControlProfile {
    return ParentalControlProfile(
        id = id,
        name = name,
        pinHash = pinHash,
        blockedKeywords = blockedKeywordsCsv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() },
        lockedSettings = lockedSettings,
        enabled = enabled,
        createdAt = createdAt
    )
}

fun ParentalControlProfile.toEntity(): ParentalProfileEntity {
    return ParentalProfileEntity(
        id = id,
        name = name,
        pinHash = pinHash,
        blockedKeywordsCsv = blockedKeywords.joinToString(","),
        lockedSettings = lockedSettings,
        enabled = enabled,
        createdAt = createdAt
    )
}

fun ChannelMetadataEntity.toModel(): ChannelMetadata {
    return ChannelMetadata(
        channelId = channelId,
        normalizedName = normalizedName,
        country = country,
        language = language,
        category = category,
        resolvedLogoUrl = resolvedLogoUrl,
        manualLogoUrl = manualLogoUrl,
        metadataSource = metadataSource,
        updatedAt = updatedAt
    )
}

fun ChannelMetadata.toEntity(): ChannelMetadataEntity {
    return ChannelMetadataEntity(
        channelId = channelId,
        normalizedName = normalizedName,
        country = country,
        language = language,
        category = category,
        resolvedLogoUrl = resolvedLogoUrl,
        manualLogoUrl = manualLogoUrl,
        metadataSource = metadataSource,
        updatedAt = updatedAt
    )
}

fun TvHomeChannelEntity.toModel(): TvHomeChannelState {
    return TvHomeChannelState(
        type = runCatching { TvHomeChannelType.valueOf(type) }.getOrDefault(TvHomeChannelType.RECENT_CHANNELS),
        providerChannelId = providerChannelId,
        enabled = enabled,
        lastPublishedAt = lastPublishedAt
    )
}

fun TvHomeChannelState.toEntity(): TvHomeChannelEntity {
    return TvHomeChannelEntity(
        type = type.name,
        providerChannelId = providerChannelId,
        enabled = enabled,
        lastPublishedAt = lastPublishedAt
    )
}
