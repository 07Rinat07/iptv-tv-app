package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.domain.repository.EpgSettingsRepository
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.HistoryRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ChannelStableIdentity
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EpgTimeCorrection
import com.iptv.tv.core.model.ParentalControlSettings
import com.iptv.tv.core.model.PlaybackHistoryItem
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_RECENT_CHANNELS_SOURCE
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest

/**
 * Adds a bounded, newest-first Recent aggregate over the existing All Channels/Favorites chain.
 *
 * History stores legacy channel IDs. Resolution therefore uses bounded live-channel candidates and
 * durable Favorites representatives before applying the current parental policy. The returned
 * [Channel] keeps the selected source playlist ID and stream provenance for the existing Player
 * route; no physical playlist row is created.
 *
 * Both explicit Recent browsing and playlist metadata resolve only the bounded history-ID window.
 * Home/playlist observation keeps a separate count flow so it does not materialize the Recent
 * channel list merely to calculate channelCount.
 *
 * This is also the outermost [PlaylistRepository] decorator. User-requested EPG clock correction
 * is intentionally applied here so guide, Player and recording callers observe one consistent
 * corrected timeline while the underlying XMLTV cache remains source-accurate.
 */
@Singleton
class VirtualRecentChannelsPlaylistRepository @Inject constructor(
    private val delegate: VirtualAllChannelsPlaylistRepository,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository,
    private val epgSettingsRepository: EpgSettingsRepository,
    private val favoritesRepository: FavoritesRepository,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val favoriteSnapshotDao: FavoriteSnapshotDao,
    private val favoriteLiveChannelResolver: FavoriteLiveChannelResolver,
    private val aggregateScope: VirtualPlaylistAggregateScope
) : PlaylistRepository by delegate {
    private val recentChannels by lazy {
        observeVirtualRecentChannels(
            history = historyRepository.observeHistory(limit = RECENT_HISTORY_LOOKBACK_LIMIT),
            parentalSettings = settingsRepository.observeParentalControlSettings(),
            channelInvalidation = favoriteChannelLookupDao.observeChannelTableInvalidation(),
            favoriteInvalidation = favoritesRepository.observeFavoriteCount(),
            favoriteVariantInvalidation = favoriteSnapshotDao.observeFavoriteVariantCount(),
            loadCandidates = ::loadRecentCandidates
        ).shareVirtualAggregate(aggregateScope)
    }
    private val recentChannelCount by lazy {
        observeVirtualRecentChannelCount(
            history = historyRepository.observeHistory(limit = RECENT_HISTORY_LOOKBACK_LIMIT),
            parentalSettings = settingsRepository.observeParentalControlSettings(),
            channelInvalidation = favoriteChannelLookupDao.observeChannelTableInvalidation(),
            favoriteInvalidation = favoritesRepository.observeFavoriteCount(),
            favoriteVariantInvalidation = favoriteSnapshotDao.observeFavoriteVariantCount(),
            loadCandidates = ::loadRecentCandidates
        )
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
        if (playlistId == VIRTUAL_RECENT_CHANNELS_PLAYLIST_ID) {
            return AppResult.Success(emptyMap())
        }
        if (endEpochMs < startEpochMs) {
            return AppResult.Error("EPG window end must not precede start")
        }

        val manualOffsetMinutes = epgSettingsRepository.currentSettings().manualOffsetMinutes
        if (manualOffsetMinutes == 0) {
            return delegate.getPlaylistEpgWindow(playlistId, startEpochMs, endEpochMs, query)
        }

        val (sourceStartMs, sourceEndMs) = EpgTimeCorrection.sourceWindowForDisplayWindow(
            displayStartEpochMs = startEpochMs,
            displayEndEpochMs = endEpochMs,
            manualOffsetMinutes = manualOffsetMinutes
        )
        return when (
            val result = delegate.getPlaylistEpgWindow(
                playlistId = playlistId,
                startEpochMs = sourceStartMs,
                endEpochMs = sourceEndMs,
                query = query
            )
        ) {
            is AppResult.Success -> AppResult.Success(
                result.data.mapValues { (_, programs) ->
                    EpgTimeCorrection.apply(programs, manualOffsetMinutes)
                        .filter { program ->
                            program.endEpochMs > startEpochMs && program.startEpochMs < endEpochMs
                        }
                }.filterValues(List<EpgProgram>::isNotEmpty)
            )
            is AppResult.Error -> result
            AppResult.Loading -> AppResult.Loading
        }
    }

    override suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo> {
        val settings = epgSettingsRepository.currentSettings()
        val baseInfo = when (val result = delegate.getChannelEpgNowNext(channelId)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> return result
            AppResult.Loading -> return AppResult.Loading
        }
        if (settings.manualOffsetMinutes == 0) {
            return AppResult.Success(baseInfo)
        }

        val nowMs = System.currentTimeMillis()
        val correctedPrograms = EpgTimeCorrection.apply(
            baseInfo.schedule,
            settings.manualOffsetMinutes
        )
        return AppResult.Success(
            baseInfo.copy(
                now = EpgTimeCorrection.current(correctedPrograms, nowMs),
                next = EpgTimeCorrection.next(correctedPrograms, nowMs),
                upcoming = correctedPrograms
                    .asSequence()
                    .filter { program -> program.endEpochMs > nowMs }
                    .take(12)
                    .toList(),
                schedule = correctedPrograms
            )
        )
    }

    private suspend fun loadRecentCandidates(
        history: List<PlaybackHistoryItem>
    ): RecentMetadataCandidates {
        return loadRecentMetadataCandidates(
            history = history,
            findChannelsByIds = favoriteChannelLookupDao::findChannelsByIds,
            findFavoritesByPreferredChannelIds =
                favoriteSnapshotDao::findFavoritesByPreferredChannelIds,
            findVariantsByLogicalKeys = favoriteSnapshotDao::findVariantsByLogicalKeys,
            findMatchingFavoriteLiveChannels = favoriteLiveChannelResolver::findMatchingChannels
        )
    }
}

internal fun observeVirtualRecentChannels(
    history: Flow<List<PlaybackHistoryItem>>,
    parentalSettings: Flow<ParentalControlSettings>,
    channelInvalidation: Flow<Int>,
    favoriteInvalidation: Flow<Int>,
    favoriteVariantInvalidation: Flow<Int>,
    loadCandidates: suspend (List<PlaybackHistoryItem>) -> RecentMetadataCandidates
): Flow<List<Channel>> {
    return combine(
        history,
        channelInvalidation,
        favoriteInvalidation,
        favoriteVariantInvalidation,
        parentalSettings.distinctUntilChanged()
    ) { historyItems, _, _, _, settings ->
        RecentChannelInput(
            history = historyItems,
            parentalGate = settings.toParentalChannelGate()
        )
    }.mapLatest { input ->
        val candidates = loadCandidates(input.history)
        recentChannelsForVirtualView(
            history = input.history,
            allChannels = candidates.allChannels,
            favoriteChannels = candidates.favoriteChannels,
            parentalGate = input.parentalGate,
            limit = MAX_RECENT_CHANNELS
        )
    }.distinctUntilChanged()
}

internal fun observeVirtualRecentChannelCount(
    history: Flow<List<PlaybackHistoryItem>>,
    parentalSettings: Flow<ParentalControlSettings>,
    channelInvalidation: Flow<Int>,
    favoriteInvalidation: Flow<Int>,
    favoriteVariantInvalidation: Flow<Int>,
    loadCandidates: suspend (List<PlaybackHistoryItem>) -> RecentMetadataCandidates
): Flow<Int> {
    return combine(
        history,
        channelInvalidation,
        favoriteInvalidation,
        favoriteVariantInvalidation,
        parentalSettings.distinctUntilChanged()
    ) { historyItems, _, _, _, settings ->
        RecentChannelInput(
            history = historyItems,
            parentalGate = settings.toParentalChannelGate()
        )
    }.mapLatest { input ->
        val candidates = loadCandidates(input.history)
        recentChannelsForVirtualView(
            history = input.history,
            allChannels = candidates.allChannels,
            favoriteChannels = candidates.favoriteChannels,
            parentalGate = input.parentalGate,
            limit = MAX_RECENT_CHANNELS
        ).size
    }.distinctUntilChanged()
}

private data class RecentChannelInput(
    val history: List<PlaybackHistoryItem>,
    val parentalGate: ParentalChannelGate
)

internal data class RecentMetadataCandidates(
    val allChannels: List<Channel>,
    val favoriteChannels: List<Channel>
)

/**
 * Loads only Channel payloads that can be selected by the bounded history window.
 *
 * Favorites keep [FavoriteChannelEntity.preferredChannelId] as their stable compatibility/history
 * ID even when the selected playback source moves to another live row or persisted variant. The
 * bounded metadata path therefore resolves candidate snapshots with the same
 * [resolvedFavoriteRepresentatives] function used by [FavoritesRepositoryFacade], then supplies
 * those representatives separately so they override same-ID live rows exactly as explicit Recent
 * browsing does.
 */
internal suspend fun loadRecentMetadataCandidates(
    history: List<PlaybackHistoryItem>,
    findChannelsByIds: suspend (List<Long>) -> List<ChannelEntity>,
    findFavoritesByPreferredChannelIds: suspend (List<Long>) -> List<FavoriteChannelEntity>,
    findVariantsByLogicalKeys: suspend (List<String>) -> List<FavoriteChannelVariantEntity>,
    findMatchingFavoriteLiveChannels: suspend (Set<String>) -> List<ChannelEntity>
): RecentMetadataCandidates {
    val historyIds = history.asSequence()
        .map(PlaybackHistoryItem::channelId)
        .filter { channelId -> channelId > 0 }
        .distinct()
        .take(RECENT_HISTORY_LOOKBACK_LIMIT)
        .toList()
    if (historyIds.isEmpty()) {
        return RecentMetadataCandidates(emptyList(), emptyList())
    }

    val liveEntities = historyIds
        .chunked(RECENT_METADATA_LOOKUP_BATCH_SIZE)
        .flatMap { ids -> findChannelsByIds(ids) }
    val favoriteSnapshots = historyIds
        .chunked(RECENT_METADATA_LOOKUP_BATCH_SIZE)
        .flatMap { ids -> findFavoritesByPreferredChannelIds(ids) }
    if (favoriteSnapshots.isEmpty()) {
        return RecentMetadataCandidates(
            allChannels = liveEntities.map(ChannelEntity::toModel),
            favoriteChannels = emptyList()
        )
    }

    val candidateLogicalKeys = favoriteSnapshots
        .map(FavoriteChannelEntity::logicalKey)
        .distinct()
    val persistedVariants = candidateLogicalKeys
        .chunked(RECENT_METADATA_LOOKUP_BATCH_SIZE)
        .flatMap { logicalKeys -> findVariantsByLogicalKeys(logicalKeys) }
    val favoriteLiveChannels = findMatchingFavoriteLiveChannels(candidateLogicalKeys.toSet())
    val favoriteRepresentatives = resolvedFavoriteRepresentatives(
        favorites = favoriteSnapshots,
        persistedVariants = persistedVariants,
        liveChannels = favoriteLiveChannels
    )

    return RecentMetadataCandidates(
        allChannels = liveEntities.map(ChannelEntity::toModel),
        favoriteChannels = favoriteRepresentatives
    )
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

internal fun ParentalControlSettings.toParentalChannelGate(): ParentalChannelGate {
    return ParentalChannelGate(
        enabled = enabled,
        hideAdultChannels = hideAdultChannels,
        blockedKeywords = blockedKeywords
    )
}

private const val RECENT_METADATA_LOOKUP_BATCH_SIZE = 256
internal const val RECENT_HISTORY_LOOKBACK_LIMIT = 250
internal const val MAX_RECENT_CHANNELS = 100