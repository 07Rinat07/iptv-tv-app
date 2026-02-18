package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.model.SyncLog
import kotlinx.coroutines.flow.Flow

interface DiagnosticsRepository {
    fun observeLogs(limit: Int = 200): Flow<List<SyncLog>>
    suspend fun addLog(status: String, message: String, playlistId: Long? = null)
}

