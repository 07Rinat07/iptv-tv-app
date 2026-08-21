package com.iptv.tv.core.data.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.FavoritesRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelEpgInfo
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.ChannelPreview
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first

/**
 * Adds the aggregate Favorites view to the legacy PlaylistRepository contract without creating a
 * physical Room playlist. This lets existing catalog navigation and Player code consume durable
 * favorites, including snapshots whose original playlist/channel rows were deleted.
 */
@Singleton
class VirtualFavoritesPlaylistRepository @Inject constructor(
    private val delegate: PlaylistRepositoryImpl,
    private val favoritesRepository: FavoritesRepository
) : PlaylistRepository by delegate {
    override fun observePlaylists(): Flow<List<Playlist>> {
        return combine(
            delegate.observePlaylists(),
            favoritesRepository.observeFavorites()
        ) { playlists, favorites ->
            playlists.filterNot { it.id == VIRTUAL_FAVORITES_PLAYLIST_ID } +
                virtualFavoritesPlaylist(channelCount = favorites.size)
        }
    }

    override fun observeChannels(playlistId: Long): Flow<List<Channel>> {
        return if (playlistId == VIRTUAL_FAVORITES_PLAYLIST_ID) {
            favoritesRepository.observeFavorites()
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
        val channels = favoritesRepository.observeFavorites().first()
        return AppResult.Success(virtualFavoritesSummary(channels))
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
    val visible = channels.filterNot(Channel::isHidden)
    val groupCounts = visible
        .mapNotNull { it.group?.trim()?.takeIf(String::isNotEmpty) }
        .groupingBy { it }
        .eachCount()
    return PlaylistContentSummary(
        playlistId = VIRTUAL_FAVORITES_PLAYLIST_ID,
        playlistName = "Избранное",
        sourceType = PlaylistSourceType.CUSTOM,
        source = VIRTUAL_FAVORITES_SOURCE,
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
            .sortedWith(compareBy<Channel> { it.orderIndex }.thenBy { it.name })
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
