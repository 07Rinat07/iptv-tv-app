package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.Channel
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    /** One representative per logical favorite channel for the dedicated Favorites screen. */
    fun observeFavorites(): Flow<List<Channel>>

    /** All concrete channel row IDs marked by the global favorite identity. */
    fun observeFavoriteChannelIds(): Flow<Set<Long>>

    suspend fun toggleFavorite(channelId: Long)
}
