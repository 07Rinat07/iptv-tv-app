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
import kotlinx.coroutines.flow.first

@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val playlistRepository: PlaylistRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val playlists = playlistRepository.observePlaylists().first()
        if (playlists.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        var sourcesChecked = 0
        var matchedChannels = 0
        var failures = 0
        val failedPlaylists = mutableListOf<Long>()

        playlists.forEach { playlist ->
            when (
                val result = playlistRepository.getPlaylistEpgWindow(
                    playlistId = playlist.id,
                    startEpochMs = now - EPG_LOOKBACK_MS,
                    endEpochMs = now + EPG_LOOKAHEAD_MS
                )
            ) {
                is AppResult.Success -> {
                    sourcesChecked += 1
                    matchedChannels += result.data.size
                }
                is AppResult.Error -> {
                    failures += 1
                    failedPlaylists += playlist.id
                }
                AppResult.Loading -> Unit
            }
        }

        diagnosticsRepository.addLog(
            status = "epg_background_refresh",
            message = buildString {
                append("EPG background refresh: playlists=")
                append(playlists.size)
                append(", refreshed=")
                append(sourcesChecked)
                append(", matchedChannels=")
                append(matchedChannels)
                append(", failures=")
                append(failures)
                if (failedPlaylists.isNotEmpty()) {
                    append(", failedPlaylistIds=")
                    append(failedPlaylists.joinToString(","))
                }
            }
        )

        val output = Data.Builder()
            .putInt(KEY_REFRESHED_PLAYLISTS, sourcesChecked)
            .putInt(KEY_MATCHED_CHANNELS, matchedChannels)
            .putInt(KEY_FAILURES, failures)
            .putLong(KEY_REFRESHED_AT_MS, System.currentTimeMillis())
            .build()

        return if (sourcesChecked > 0 || failures == 0) {
            Result.success(output)
        } else {
            Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "epg_refresh"
        const val IMMEDIATE_WORK_NAME = "epg_refresh_now"
        const val KEY_REFRESHED_PLAYLISTS = "refreshed_playlists"
        const val KEY_MATCHED_CHANNELS = "matched_channels"
        const val KEY_FAILURES = "failures"
        const val KEY_REFRESHED_AT_MS = "refreshed_at_ms"

        private const val EPG_LOOKBACK_MS = 30 * 60 * 1000L
        private const val EPG_LOOKAHEAD_MS = 12 * 60 * 60 * 1000L
    }
}
