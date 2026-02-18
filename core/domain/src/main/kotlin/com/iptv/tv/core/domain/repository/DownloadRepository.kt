package com.iptv.tv.core.domain.repository

import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.model.DownloadTask
import kotlinx.coroutines.flow.Flow

interface DownloadRepository {
    fun observeDownloads(limit: Int = 200): Flow<List<DownloadTask>>
    suspend fun enqueue(source: String): AppResult<DownloadTask>
    suspend fun pause(downloadId: Long): AppResult<Unit>
    suspend fun resume(downloadId: Long): AppResult<Unit>
    suspend fun cancel(downloadId: Long): AppResult<Unit>
    suspend fun remove(downloadId: Long): AppResult<Unit>
    suspend fun tickQueue(maxConcurrent: Int = 1): AppResult<Int>
}
