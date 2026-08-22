package com.iptv.tv.core.data.repository

import android.content.Context
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.data.mapper.toModel
import com.iptv.tv.core.data.settings.SettingsKeys
import com.iptv.tv.core.data.settings.settingsDataStore
import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
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
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adds the system-owned All channels aggregate on top of the existing virtual Favorites decorator.
 *
 * Concrete channels keep their original Room IDs and playlist provenance. Only the containing
 * playlist is virtual, so canonical catalog navigation and the existing Player route are reused.
 */
@Singleton
class VirtualAllChannelsPlaylistRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val delegate: VirtualFavoritesPlaylistRepository,
    private val favoriteChannelLookupDao: FavoriteChannelLookupDao
) : PlaylistRepository by delegate {
    override fun observePlaylists(): Flow<List<Playlist>> {
        return combine(
            delegate.observePlaylists(),
            observeAllChannels()
        ) { playlists, channels ->
            playlists.filterNot { it.id == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID } +
                virtualAllChannelsPlaylist(channelCount = channels.size)
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return if (playlistId == VIRTUAL_ALL_CHANNELS_PLAYLIST_ID) {
            observeAllChannels()
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
        return AppResult.Success(virtualAllChannelsSummary(observeAllChannels().first()))
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

    private fun observeAllChannels(): Flow<List<Channel>> {
        return favoriteChannelLookupDao.observeAllChannels()
            .combine(observeParentalChannelGate()) { rows, parentalGate ->
                allChannelsForVirtualView(rows, parentalGate)
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
        }
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
    val visible = channels.filterNot(Channel::isHidden)
    val groupCounts = visible
        .mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }
        .eachCount()
    return PlaylistContentSummary(
        playlistId = VIRTUAL_ALL_CHANNELS_PLAYLIST_ID,
        playlistName = "Все каналы",
        sourceType = PlaylistSourceType.CUSTOM,
        source = VIRTUAL_ALL_CHANNELS_SOURCE,
        epgSourceUrl = null,
        totalChannels = channels.size,
        visibleChannels = visible.size,
        hiddenChannels = channels.count(Channel::isHidden),
        channelsWithLogo = visible.count { !it.logo.isNullOrBlank() },
        channelsWithTvgId = visible.count { !it.tvgId.isNullOrBlank() },
        availableChannels = visible.count { it.health == ChannelHealth.AVAILABLE },
        unstableChannels = visible.count { it.health == ChannelHealth.UNSTABLE },
        unavailableChannels = visible.count { it.health == ChannelHealth.UNAVAILABLE },
        unknownHealthChannels = visible.count { it.health == ChannelHealth.UNKNOWN },
        groupCount = groupCounts.size,
        topGroups = groupCounts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(10)
            .map { it.key to it.value },
        channelPreviews = visible
            .sortedWith(
                compareBy<Channel> { it.playlistId }
                    .thenBy { it.orderIndex }
                    .thenBy { it.name }
            )
            .take(50)
            .map { channel ->
                ChannelPreview(
                    id = channel.id,
                    name = channel.name,
                    group = channel.group,
                    logo = channel.logo,
                    health = channel.health,
                    isHidden = channel.isHidden
                )
            }
    )
}
