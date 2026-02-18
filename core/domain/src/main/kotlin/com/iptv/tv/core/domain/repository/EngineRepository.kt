package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.Flow

interface EngineRepository {
    suspend fun connect(endpoint: String): AppResult<Unit>
    suspend fun refreshStatus(): AppResult<EngineStatus>
    fun observeStatus(): Flow<EngineStatus>
    suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String>
}
