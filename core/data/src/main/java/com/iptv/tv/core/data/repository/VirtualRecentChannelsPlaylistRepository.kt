package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelStableIdentity
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_SOURCE
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adds a bounded, newest-first Recent aggregate over the existing All Channels/Favorites chain.
 *
 * History stores legacy channel IDs. Resolution therefore uses both live All Channels rows and
 * durable Favorites representatives before applying the current parental policy. The returned
 * [Channel] keeps the selected source playlist ID and stream provenance for the existing Player
 * route; no physical playlist row is created.
 */
@Singleton
class VirtualRecentChannelsPlaylistRepository @Inject constructor(
    private val delegate: VirtualAllChannelsPlaylistRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val aggregateScope: VirtualPlaylistAggregateScope
) : PlaylistRepository by delegate {
    private val recentChannels by lazy {
        combine(
            historyRepository.observeHistory(limit = RECENT_HISTORY_LOOKBACK_LIMIT),
            delegate.observeChannels(VIRTUAL_ALL_CHANNELS_PLAYLIST_ID),
            delegate.observeChannels(VIRTUAL_FAVORITES_PLAYLIST_ID),
            settingsRepository.observeParentalControlSettings().distinctUntilChanged()
        ) { history, allChannels, favoriteChannels, parentalSettings ->
            recentChannelsForVirtualView(
                history = history,
                allChannels = allChannels,
                favoriteChannels = favoriteChannels,
                parentalGate = parentalSettings.toParentalChannelGate(),
                limit = MAX_RECENT_CHANNELS
            )
        }.shareVirtualAggregate(aggregateScope)
    }
    private val recentChannelCount by lazy {
        recentChannels
            .map { channels -> channels.size }
            .distinctUntilChanged()
    }
    private val recentChannelsSummary by lazy {
        recentChannels
            .map(::virtualRecentChannelsSummary)
            .shareVirtualAggregate(aggregateScope)
    }

    override fun observePlaylists(): Flow<List<Playlist>> {
        return combine(
            delegate.observePlaylists(),
            recentChannelCount
        ) { playlists, channelCount ->
            playlists.filterNot { it.id == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID } +
                virtualRecentChannelsPlaylist(channelCount = channelCount)
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            recentChannels
        } else {
            delegate.observeChannels(playlistId)
        }
    }

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            AppResult.Success(Unit)
        } else {
            delegate.refreshPlaylist(playlistId)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("Виртуальный список Недавние нельзя удалить")
        } else {
            delegate.deletePlaylist(playlistId)
        }
    }

    override suspend fun setPlaylistEpgSource(
        playlistId: Long,
        epgSourceUrl: String?
    ): AppResult<Unit> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("EPG задаётся на исходных плейлистах, а не на виртуальном списке Недавние")
        } else {
            delegate.setPlaylistEpgSource(playlistId, epgSourceUrl)
        }
    }

    override suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("Виртуальный список Недавние не требует проверки плейлиста")
        } else {
            delegate.validatePlaylist(playlistId)
        }
    }

    override suspend fun getPlaylistContentSummary(
        playlistId: Long
    ): AppResult<PlaylistContentSummary> {
        if (playlistId != VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            return delegate.getPlaylistContentSummary(playlistId)
        }
        return AppResult.Success(recentChannelsSummary.first())
    }

    override suspend fun getPlaylistEpgWindow(
        playlistId: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String?
    ): AppResult<Map<Long, List<EpgProgram>>> {
        return if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            AppResult.Success(emptyMap())
        } else {
            delegate.getPlaylistEpgWindow(playlistId, startEpochMs, endEpochMs, query)
        }
    }
}

internal fun recentChannelsForVirtualView(
    history: List<PlaybackHistoryItem>,
    allChannels: List<Channel>,
    favoriteChannels: List<Channel>,
    parentalGate: ParentalChannelGate,
    limit: Int = MAX_RECENT_CHANNELS
): List<Channel> {
    if (limit <= 0) return emptyList()

    val channelsByLegacyId = buildMap {
        allChannels.forEach { channel -> put(channel.id, channel) }
        // A durable Favorite may replace a stale live row with its selected persisted source.
        favoriteChannels.forEach { channel -> put(channel.id, channel) }
    }
    val seenHistoryChannelIds = mutableSetOf<Long>()
    val seenLogicalChannels = mutableSetOf<String>()

    return history.asSequence()
        .filter { item -> item.channelId > 0 }
        .sortedWith(
            compareByDescending<PlaybackHistoryItem> { it.playedAt }
                .thenByDescending { it.id }
        )
        .filter { item -> seenHistoryChannelIds.add(item.channelId) }
        .mapNotNull { item -> channelsByLegacyId[item.channelId] }
        .filterNot(Channel::isHidden)
        .filterNot { channel ->
            ParentalChannelFilter.isBlocked(
                name = channel.name,
                groupName = channel.group,
                tvgId = channel.tvgId,
                gate = parentalGate
            )
        }
        .filter { channel ->
            seenLogicalChannels.add(
                ChannelStableIdentity.key(
                    tvgId = channel.tvgId,
                    name = channel.name,
                    streamUrl = channel.streamUrl
                )
            )
        }
        .take(limit)
        .mapIndexed { recentIndex, channel -> channel.copy(orderIndex = recentIndex) }
        .toList()
}

internal fun virtualRecentChannelsPlaylist(channelCount: Int): Playlist = Playlist(
    id = VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID,
    name = "Недавние",
    sourceType = PlaylistSourceType.CUSTOM,
    source = VIRTUAL_RECENT_CHANNELS_SOURCE,
    epgSourceUrl = null,
    scheduleHours = 0,
    lastSyncedAt = null,
    channelCount = channelCount.coerceAtLeast(0),
    isCustom = false,
    catalogOrigin = CatalogOriginKind.SYSTEM
)

internal fun virtualRecentChannelsSummary(channels: List<Channel>): PlaylistContentSummary {
    val recentOrderByChannelId = channels
        .mapIndexed { index, channel -> channel.id to index }
        .toMap()
    return virtualPlaylistContentSummary(
        playlistId = VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID,
        playlistName = "Недавние",
        source = VIRTUAL_RECENT_CHANNELS_SOURCE,
        channels = channels,
        previewComparator = compareBy { channel ->
            recentOrderByChannelId[channel.id] ?: Int.MAX_VALUE
        }
    )
}

private fun ParentalControlSettings.toParentalChannelGate(): ParentalChannelGate {
    return ParentalChannelGate(
        enabled = enabled,
        hideAdultChannels = hideAdultChannels,
        blockedKeywords = blockedKeywords
    )
}

internal const val RECENT_HISTORY_LOOKBACK_LIMIT = 250
internal const val MAX_RECENT_CHANNELS = 100
