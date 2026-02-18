package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.PlaybackHistoryItem
import kotlinx.coroutines.flow.Flow

interface HistoryRepository {
    fun observeHistory(limit: Int = 200): Flow<List<PlaybackHistoryItem>>
    suspend fun add(channelId: Long, channelName: String)
    suspend fun clear()
}
