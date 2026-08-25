package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.ParentalChannelGateRow
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_ALL_CHANNELS_SOURCE
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adds the system-owned All channels aggregate on top of the existing virtual Favorites decorator.
 *
 * Normal Home/playlist observation must not materialize every ChannelEntity just to show the
 * aggregate count. The full catalog stream is subscribed only when the All-channels catalog (or
 * its detailed summary) is explicitly requested.
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

    private val allChannelCount = combine(
        favoriteChannelLookupDao.observeVisibleChannelCount(),
        favoriteChannelLookupDao.observeVisibleParentalGateRows(),
        parentalGate
    ) { visibleCount, parentalRows, gate ->
        virtualAllChannelCount(
            visibleCount = visibleCount,
            parentalRows = parentalRows,
            parentalGate = gate
        )
    }.distinctUntilChanged()

    private val allChannelsSummary = allChannels
        .map(::virtualAllChannelsSummary)
        .shareVirtualAggregate(aggregateScope)

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
        return AppResult.Success(allChannelsSummary.first())
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
