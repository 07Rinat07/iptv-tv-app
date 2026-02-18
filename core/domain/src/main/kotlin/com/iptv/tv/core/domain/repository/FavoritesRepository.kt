package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.Channel
import kotlinx.coroutines.flow.Flow

interface FavoritesRepository {
    fun observeFavorites(): Flow<List<Channel>>
    suspend fun toggleFavorite(channelId: Long)
}
