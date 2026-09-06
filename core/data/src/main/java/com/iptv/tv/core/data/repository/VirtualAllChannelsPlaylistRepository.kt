package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.database.dao.AllChannelsGroupCountRow
import com.iptv.tv.core.database.dao.AllChannelsParentalSummaryRow
import com.iptv.tv.core.database.dao.AllChannelsSummaryAggregateRow
import com.iptv.tv.core.database.dao.AllChannelsSummaryPreviewRow
import com.iptv.tv.core.database.dao.AllChannelsSummarySnapshot
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.ParentalChannelGateRow
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelPreview
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_SOURCE
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.PriorityQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adds the system-owned All channels aggregate on top of the existing virtual Favorites decorator.
 *
 * Normal Home/playlist observation uses scalar or narrow count projections. Explicit All-channels
 * browsing still materializes full channels, while its one-shot summary uses a consistent bounded
 * SQL snapshot or a transactionally paged narrow projection when parental keywords are active.
 */
@Singleton
class VirtualAllChannelsPlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val delegate: VirtualFavoritesPlaylistRepository,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao,
    private val aggregateScope: VirtualPlaylistAggregateScope
) : PlaylistRepository by delegate {
    private val parentalGate = observeParentalChannelGate()
        .shareVirtualAggregate(aggregateScope)

    private val allChannels = favoriteChannelLookupDao.observeAllChannels()
        .combine(parentalGate) { rows, gate ->
            allChannelsForVirtualView(rows, gate)
        }
        .shareVirtualAggregate(aggregateScope)

    private val allChannelCount = observeVirtualAllChannelCount(
        parentalGate = parentalGate,
        visibleCount = favoriteChannelLookupDao::observeVisibleChannelCount,
        parentalRows = favoriteChannelLookupDao::observeVisibleParentalGateRows
    )

    override fun observePlaylists(): Flow<List<Playlist>> {
        return combine(
            delegate.observePlaylists(),
            allChannelCount
        ) { playlists, channelCount ->
            playlists.filterNot { it.id == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID } +
                virtualAllChannelsPlaylist(channelCount = channelCount)
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            allChannels
        } else {
            delegate.observeChannels(playlistId)
        }
    }

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            AppResult.Success(Unit)
        } else {
            delegate.refreshPlaylist(playlistId)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("Виртуальный список Все каналы нельзя удалить")
        } else {
            delegate.deletePlaylist(playlistId)
        }
    }

    override suspend fun setPlaylistEpgSource(
        playlistId: Long,
        epgSourceUrl: String?
    ): AppResult<Unit> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("EPG задаётся на исходных плейлистах, а не на виртуальном списке Все каналы")
        } else {
            delegate.setPlaylistEpgSource(playlistId, epgSourceUrl)
        }
    }

    override suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            AppResult.Error("Виртуальный список Все каналы не требует проверки плейлиста")
        } else {
            delegate.validatePlaylist(playlistId)
        }
    }

    override suspend fun getPlaylistContentSummary(
        playlistId: Long
    ): AppResult<PlaylistContentSummary> {
        if (playlistId != VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            return delegate.getPlaylistContentSummary(playlistId)
        }
        return AppResult.Success(
            loadVirtualAllChannelsSummary(
                parentalGate = parentalGate.first(),
                snapshot = favoriteChannelLookupDao::getAllChannelsSummarySnapshot,
                parentalPages = favoriteChannelLookupDao::visitAllChannelsParentalSummaryPages
            )
        )
    }

    override suspend fun getPlaylistEpgWindow(
        playlistId: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String?
    ): AppResult<Map<Long, List<EpgProgram>>> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            // Avoid an N-source EPG fan-out from a system aggregate. Individual concrete channels
            // retain their IDs and can still resolve source-owned now/next EPG through the delegate.
            AppResult.Success(emptyMap())
        } else {
            delegate.getPlaylistEpgWindow(playlistId, startEpochMs, endEpochMs, query)
        }
    }

    private fun observeParentalChannelGate(): Flow<ParentalChannelGate> {
        return context.settingsDataStore.data.map { prefs ->
            ParentalChannelGate(
                enabled = prefs[SettingsKeys.parentalEnabled] ?: false,
                hideAdultChannels = prefs[SettingsKeys.parentalHideAdultChannels] ?: true,
                blockedKeywords = ParentalChannelFilter.decodeKeywords(
                    prefs[SettingsKeys.parentalBlockedKeywords]
                )
            )
        }.distinctUntilChanged()
    }
}

internal suspend fun loadVirtualAllChannelsSummary(
    parentalGate: ParentalChannelGate,
    snapshot: suspend (groupLimit: Int, previewLimit: Int) -> AllChannelsSummarySnapshot,
    parentalPages: suspend ((List<AllChannelsParentalSummaryRow>) -> Unit) -> Unit
): PlaylistContentSummary {
    return if (parentalGate.blocksChannels) {
        val accumulator = VirtualAllChannelsParentalSummaryAccumulator(parentalGate)
        parentalPages(accumulator::accept)
        accumulator.build()
    } else {
        val summarySnapshot = snapshot(
            VIRTUAL_PLAYLIST_TOP_GROUP_LIMIT,
            VIRTUAL_PLAYLIST_PREVIEW_LIMIT
        )
        virtualAllChannelsSqlSummary(
            aggregate = summarySnapshot.aggregate,
            topGroups = summarySnapshot.topGroups,
            previews = summarySnapshot.previews
        )
    }
}

internal fun observeVirtualAllChannelCount(
    parentalGate: Flow<ParentalChannelGate>,
    visibleCount: () -> Flow<Int>,
    parentalRows: () -> Flow<List<ParentalChannelGateRow>>
): Flow<Int> = channelFlow {
    parentalGate.collectLatest { gate ->
        if (!gate.blocksChannels) {
            visibleCount().collect { count ->
                send(count.coerceAtLeast(0))
            }
        } else {
            parentalRows().collect { rows ->
                send(
                    virtualAllChannelCount(
                        visibleCount = 0,
                        parentalRows = rows,
                        parentalGate = gate
                    )
                )
            }
        }
    }
}.distinctUntilChanged()

internal fun virtualAllChannelCount(
    visibleCount: Int,
    parentalRows: List<ParentalChannelGateRow>,
    parentalGate: ParentalChannelGate
): Int {
    if (!parentalGate.blocksChannels) return visibleCount.coerceAtLeast(0)
    return parentalRows.count { row ->
        !ParentalChannelFilter.isBlocked(
            name = row.name,
            groupName = row.groupName,
            tvgId = row.tvgId,
            gate = parentalGate
        )
    }
}

internal fun allChannelsForVirtualView(
    rows: List<ChannelEntity>,
    parentalGate: ParentalChannelGate
): List<Channel> {
    return rows.asSequence()
        .filterNot { row ->
            ParentalChannelFilter.isBlocked(
                name = row.name,
                groupName = row.groupName,
                tvgId = row.tvgId,
                gate = parentalGate
            )
        }
        .map { row -> row.toModel() }
        .toList()
}

internal fun virtualAllChannelsPlaylist(channelCount: Int): Playlist = Playlist(
    id = VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
    name = "Все каналы",
    sourceType = PlaylistSourceType.CUSTOM,
    source = VIRTUAL_ALL_CHANNELS_SOURCE,
    epgSourceUrl = null,
    scheduleHours = 0,
    lastSyncedAt = null,
    channelCount = channelCount.coerceAtLeast(0),
    isCustom = false,
    catalogOrigin = CatalogOriginKind.SYSTEM
)

internal fun virtualAllChannelsSummary(channels: List<Channel>): PlaylistContentSummary {
    return virtualPlaylistContentSummary(
        playlistId = VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
        playlistName = "Все каналы",
        source = VIRTUAL_ALL_CHANNELS_SOURCE,
        channels = channels,
        previewComparator = compareBy<Channel> { it.playlistId }
            .thenBy { it.orderIndex }
            .thenBy { it.name }
    )
}

internal fun virtualAllChannelsSqlSummary(
    aggregate: AllChannelsSummaryAggregateRow,
    topGroups: List<AllChannelsGroupCountRow>,
    previews: List<AllChannelsSummaryPreviewRow>
): PlaylistContentSummary {
    return PlaylistContentSummary(
        playlistId = VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
        playlistName = "Все каналы",
        sourceType = PlaylistSourceType.CUSTOM,
        source = VIRTUAL_ALL_CHANNELS_SOURCE,
        epgSourceUrl = null,
        totalChannels = aggregate.totalChannels,
        visibleChannels = aggregate.visibleChannels,
        hiddenChannels = aggregate.hiddenChannels,
        channelsWithLogo = aggregate.channelsWithLogo,
        channelsWithTvgId = aggregate.channelsWithTvgId,
        availableChannels = aggregate.availableChannels,
        unstableChannels = aggregate.unstableChannels,
        unavailableChannels = aggregate.unavailableChannels,
        unknownHealthChannels = aggregate.unknownHealthChannels,
        groupCount = aggregate.groupCount,
        topGroups = topGroups.map { row -> row.groupName to row.channelCount },
        channelPreviews = previews.map(::summaryPreview)
    )
}

internal fun virtualAllChannelsParentalSummary(
    rows: List<AllChannelsParentalSummaryRow>,
    parentalGate: ParentalChannelGate
): PlaylistContentSummary {
    val accumulator = VirtualAllChannelsParentalSummaryAccumulator(parentalGate)
    accumulator.accept(rows)
    return accumulator.build()
}

internal class VirtualAllChannelsParentalSummaryAccumulator(
    private val parentalGate: ParentalChannelGate
) {
    private val groupCounts = mutableMapOf<String, Int>()
    private var totalChannels = 0
    private var visibleChannels = 0
    private var hiddenChannels = 0
    private var channelsWithLogo = 0
    private var channelsWithTvgId = 0
    private var availableChannels = 0
    private var unstableChannels = 0
    private var unavailableChannels = 0
    private var unknownHealthChannels = 0

    private val previewComparator = compareBy<AllChannelsParentalSummaryRow> { it.playlistId }
        .thenBy { it.orderIndex }
        .thenBy { it.name }
        .thenBy { it.id }
    private val previewQueue = PriorityQueue(
        VIRTUAL_PLAYLIST_PREVIEW_LIMIT,
        previewComparator.reversed()
    )

    fun accept(rows: List<AllChannelsParentalSummaryRow>) {
        rows.forEach(::accept)
    }

    private fun accept(row: AllChannelsParentalSummaryRow) {
        if (
            ParentalChannelFilter.isBlocked(
                name = row.name,
                groupName = row.groupName,
                tvgId = row.tvgId,
                gate = parentalGate
            )
        ) {
            return
        }

        totalChannels++
        if (row.isHidden) {
            hiddenChannels++
            return
        }

        visibleChannels++
        if (!row.logo.isNullOrBlank()) channelsWithLogo++
        if (!row.tvgId.isNullOrBlank()) channelsWithTvgId++
        when (ChannelHealth.valueOf(row.health)) {
            ChannelHealth.AVAILABLE -> availableChannels++
            ChannelHealth.UNSTABLE -> unstableChannels++
            ChannelHealth.UNAVAILABLE -> unavailableChannels++
            ChannelHealth.UNKNOWN -> unknownHealthChannels++
        }

        row.groupName
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?.let { group -> groupCounts[group] = (groupCounts[group] ?: 0) + 1 }

        if (previewQueue.size < VIRTUAL_PLAYLIST_PREVIEW_LIMIT) {
            previewQueue.add(row)
        } else if (previewComparator.compare(row, previewQueue.peek()) < 0) {
            previewQueue.poll()
            previewQueue.add(row)
        }
    }

    fun build(): PlaylistContentSummary {
        return PlaylistContentSummary(
            playlistId = VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
            playlistName = "Все каналы",
            sourceType = PlaylistSourceType.CUSTOM,
            source = VIRTUAL_ALL_CHANNELS_SOURCE,
            epgSourceUrl = null,
            totalChannels = totalChannels,
            visibleChannels = visibleChannels,
            hiddenChannels = hiddenChannels,
            channelsWithLogo = channelsWithLogo,
            channelsWithTvgId = channelsWithTvgId,
            availableChannels = availableChannels,
            unstableChannels = unstableChannels,
            unavailableChannels = unavailableChannels,
            unknownHealthChannels = unknownHealthChannels,
            groupCount = groupCounts.size,
            topGroups = groupCounts.entries
                .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(VIRTUAL_PLAYLIST_TOP_GROUP_LIMIT)
                .map { it.key to it.value },
            channelPreviews = previewQueue
                .toList()
                .sortedWith(previewComparator)
                .map { row ->
                    ChannelPreview(
                        id = row.id,
                        name = row.name,
                        group = row.groupName,
                        logo = row.logo,
                        health = ChannelHealth.valueOf(row.health),
                        isHidden = row.isHidden
                    )
                }
        )
    }
}

private fun summaryPreview(row: AllChannelsSummaryPreviewRow): ChannelPreview {
    return ChannelPreview(
        id = row.id,
        name = row.name,
        group = row.groupName,
        logo = row.logo,
        health = ChannelHealth.valueOf(row.health),
        isHidden = row.isHidden
    )
}
