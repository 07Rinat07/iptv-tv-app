package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistContentSummary
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.PlaylistValidationReport
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_SOURCE
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Adds the aggregate Favorites view to the legacy PlaylistRepository contract without creating a
 * physical Room playlist. This lets existing catalog navigation and Player code consume durable
 * favorites, including snapshots whose original playlist/channel rows were deleted.
 */
@Singleton
class VirtualFavoritesPlaylistRepository @Inject constructor(
    private val delegate: ReadyCatalogPlaylistRepository,
    private val favoritesRepository: FavoritesRepository,
    private val aggregateScope: VirtualPlaylistAggregateScope
) : PlaylistRepository by delegate {
    private val favoriteChannels = favoritesRepository.observeFavorites()
        .shareVirtualAggregate(aggregateScope)
    private val favoriteChannelCount = observeVirtualFavoriteCount(favoritesRepository)
    private val favoriteSummary = favoriteChannels
        .map(::virtualFavoritesSummary)
        .shareVirtualAggregate(aggregateScope)

    override fun observePlaylists(): Flow<List<Playlist>> {
        return kotlinx.coroutines.flow.combine(
            delegate.observePlaylists(),
            favoriteChannelCount
        ) { playlists, channelCount ->
            playlists.filterNot { it.id == VIRTUAL_FAVORITES_PLAYLIST_ID } +
                virtualFavoritesPlaylist(channelCount = channelCount)
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            favoriteChannels
        } else {
            delegate.observeChannels(playlistId)
        }
    }

    override suspend fun refreshPlaylist(playlistId: Long): AppResult<Unit> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            AppResult.Success(Unit)
        } else {
            delegate.refreshPlaylist(playlistId)
        }
    }

    override suspend fun deletePlaylist(playlistId: Long): AppResult<Int> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            AppResult.Error("Виртуальный список Избранное нельзя удалить")
        } else {
            delegate.deletePlaylist(playlistId)
        }
    }

    override suspend fun setPlaylistEpgSource(
        playlistId: Long,
        epgSourceUrl: String?
    ): AppResult<Unit> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            AppResult.Error("EPG задаётся на исходных плейлистах, а не на виртуальном Избранном")
        } else {
            delegate.setPlaylistEpgSource(playlistId, epgSourceUrl)
        }
    }

    override suspend fun validatePlaylist(playlistId: Long): AppResult<PlaylistValidationReport> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            AppResult.Error("Виртуальное Избранное не требует проверки плейлиста")
        } else {
            delegate.validatePlaylist(playlistId)
        }
    }

    override suspend fun getChannelById(channelId: Long): AppResult<Channel> {
        val favoriteContext = favoritesRepository.resolvePlaybackContext(channelId)
        return if (favoriteContext != null) {
            AppResult.Success(favoriteContext.channel)
        } else {
            delegate.getChannelById(channelId)
        }
    }

    override suspend fun getPlaylistContentSummary(
        playlistId: Long
    ): AppResult<PlaylistContentSummary> {
        if (playlistId != VIRTUAL_FAVORITES_PLAYLIST_ID) {
            return delegate.getPlaylistContentSummary(playlistId)
        }
        return AppResult.Success(favoriteSummary.first())
    }

    override suspend fun getChannelEpgNowNext(channelId: Long): AppResult<ChannelEpgInfo> {
        val direct = delegate.getChannelEpgNowNext(channelId)
        if (direct is AppResult.Success) return direct

        val context = favoritesRepository.resolvePlaybackContext(channelId) ?: return direct
        if (context.isLiveVariant) return direct
        val channel = context.channel
        return AppResult.Success(
            ChannelEpgInfo(
                channelId = channel.id,
                channelName = channel.name,
                tvgId = channel.tvgId,
                epgSourceUrl = null,
                matchedBy = "favorite_snapshot",
                now = null,
                next = null,
                upcoming = emptyList()
            )
        )
    }

    override suspend fun getPlaylistEpgWindow(
        playlistId: Long,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String?
    ): AppResult<Map<Long, List<EpgProgram>>> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            // EPG remains owned by each source playlist. Avoid an N-source network fan-out from the
            // virtual aggregate; individual live channels can still resolve their source EPG.
            AppResult.Success(emptyMap())
        } else {
            delegate.getPlaylistEpgWindow(playlistId, startEpochMs, endEpochMs, query)
        }
    }
}

internal fun observeVirtualFavoriteCount(
    favoritesRepository: FavoritesRepository
): Flow<Int> = favoritesRepository.observeFavoriteCount()
    .distinctUntilChanged()

internal fun virtualFavoritesPlaylist(channelCount: Int): Playlist = Playlist(
    id = VIRTUAL_FAVORITES_PLAYLIST_ID,
    name = "Избранное",
    sourceType = PlaylistSourceType.CUSTOM,
    source = VIRTUAL_FAVORITES_SOURCE,
    epgSourceUrl = null,
    scheduleHours = 0,
    lastSyncedAt = null,
    channelCount = channelCount.coerceAtLeast(0),
    isCustom = false,
    catalogOrigin = CatalogOriginKind.SYSTEM
)

internal fun virtualFavoritesSummary(channels: List<Channel>): PlaylistContentSummary {
    return virtualPlaylistContentSummary(
        playlistId = VIRTUAL_FAVORITES_PLAYLIST_ID,
        playlistName = "Избранное",
        source = VIRTUAL_FAVORITES_SOURCE,
        channels = channels,
        previewComparator = compareBy<Channel> { it.orderIndex }.thenBy { it.name }
    )
}
