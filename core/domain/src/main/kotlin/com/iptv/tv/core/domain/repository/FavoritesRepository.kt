package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.FavoritePlaybackContext
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    /** One representative per logical favorite channel for the dedicated Favorites screen. */
    fun observeFavorites(): Flow<List<Channel>>

    /** All concrete channel row IDs marked by the global favorite identity. */
    fun observeFavoriteChannelIds(): Flow<Set<Long>>

    suspend fun toggleFavorite(channelId: Long)

    /**
     * Resolve the best playable source for a favorite represented by [favoriteChannelId].
     *
     * The default keeps compatibility with test doubles and alternate implementations while the
     * unified persistence implementation provides durable live/persisted variant selection.
     */
    suspend fun resolvePlaybackContext(favoriteChannelId: Long): FavoritePlaybackContext? = null
}
