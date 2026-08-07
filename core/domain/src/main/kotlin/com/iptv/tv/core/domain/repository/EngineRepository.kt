package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.EngineStatus
import kotlinx.coroutines.flow.Flow

interface EngineRepository {
    suspend fun connect(endpoint: String): AppResult<Unit>
    suspend fun refreshStatus(): AppResult<EngineStatus>
    fun observeStatus(): Flow<EngineStatus>
    suspend fun resolveTorrentStream(magnetOrAce: String): AppResult<String>

    /** Stops the currently active embedded torrent stream, if any. */
    suspend fun stopTorrentStream(): AppResult<Unit> = AppResult.Success(Unit)

    /**
     * Requests non-blocking torrent cleanup for UI teardown paths where the caller cannot suspend.
     * Implementations that do not own an embedded torrent backend may keep the default no-op.
     */
    fun releaseTorrentStream() = Unit
}
