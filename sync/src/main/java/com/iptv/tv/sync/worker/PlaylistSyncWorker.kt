package com.iptv.tv.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PlaylistSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val playlistRepository: PlaylistRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val playlistId = inputData.getLong(KEY_PLAYLIST_ID, -1L)
        diagnosticsRepository.addLog(
            status = "sync_start",
            message = if (playlistId > 0) "Sync start for playlist=$playlistId" else "Sync start for all playlists",
            playlistId = playlistId.takeIf { it > 0 }
        )

        val refreshResult = if (playlistId > 0) {
            when (val result = playlistRepository.refreshPlaylist(playlistId)) {
                is AppResult.Success -> AppResult.Success(1)
                is AppResult.Error -> AppResult.Error(result.message, result.cause)
                AppResult.Loading -> AppResult.Loading
            }
        } else {
            playlistRepository.refreshAllPlaylists()
        }

        return when (refreshResult) {
            is AppResult.Success -> {
                diagnosticsRepository.addLog(
                    status = "sync_ok",
                    message = "Sync success, refreshed=${refreshResult.data}",
                    playlistId = playlistId.takeIf { it > 0 }
                )
                Result.success(
                    Data.Builder()
                        .putInt(KEY_REFRESHED_COUNT, refreshResult.data)
                        .build()
                )
            }
            is AppResult.Error -> {
                diagnosticsRepository.addLog(
                    status = "sync_error",
                    message = refreshResult.message,
                    playlistId = playlistId.takeIf { it > 0 }
                )
                Result.retry()
            }
            AppResult.Loading -> Result.retry()
        }
    }

    companion object {
        const val KEY_PLAYLIST_ID = "playlist_id"
        const val KEY_REFRESHED_COUNT = "refreshed_count"
        const val WORK_NAME = "playlist_sync"
    }
}
