package com.iptv.tv.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.EpgSettingsRepository
import com.iptv.tv.core.domain.repository.PlaylistRepository
import com.iptv.tv.core.model.EpgSettingsPolicy
import com.iptv.tv.core.model.EpgUserSettings
import com.iptv.tv.core.model.Playlist
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltWorker
class EpgRefreshWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val playlistRepository: PlaylistRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val epgSettingsRepository: EpgSettingsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = REFRESH_MUTEX.withLock {
        val settings = epgSettingsRepository.currentSettings()
        val forceRefresh = inputData.getBoolean(KEY_FORCE_REFRESH, false)
        if (!shouldRunEpgRefresh(forceRefresh, settings)) {
            return@withLock Result.success(
                Data.Builder()
                    .putBoolean(KEY_SKIPPED_FRESH, true)
                    .build()
            )
        }
        refreshSequentially()
    }

    private suspend fun refreshSequentially(): Result {
        val playlists = eligibleEpgRefreshPlaylists(playlistRepository.observePlaylists().first())
        if (playlists.isEmpty()) return Result.success()

        val now = System.currentTimeMillis()
        var sourcesChecked = 0
        var matchedChannels = 0
        var failures = 0
        val failedPlaylists = mutableListOf<Long>()

        // Deliberately sequential. Several XMLTV feeds can each be many megabytes and Android TV
        // boxes often have small heaps; parallel fan-out would trade latency for avoidable OOM risk.
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

        val completedAtMs = System.currentTimeMillis()
        if (failures == 0) {
            epgSettingsRepository.markSuccessfulRefresh(completedAtMs)
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
                append(", complete=")
                append(failures == 0)
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
            .putLong(KEY_REFRESHED_AT_MS, completedAtMs)
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
        const val KEY_FORCE_REFRESH = "force_refresh"
        const val KEY_SKIPPED_FRESH = "skipped_fresh"
        const val KEY_REFRESHED_PLAYLISTS = "refreshed_playlists"
        const val KEY_MATCHED_CHANNELS = "matched_channels"
        const val KEY_FAILURES = "failures"
        const val KEY_REFRESHED_AT_MS = "refreshed_at_ms"

        private val REFRESH_MUTEX = Mutex()
        private const val EPG_LOOKBACK_MS = 30 * 60 * 1000L
        private const val EPG_LOOKAHEAD_MS = 12 * 60 * 60 * 1000L
    }
}

internal fun shouldRunEpgRefresh(
    forceRefresh: Boolean,
    settings: EpgUserSettings,
    nowMs: Long = System.currentTimeMillis()
): Boolean = forceRefresh || EpgSettingsPolicy.isRefreshStale(settings, nowMs)

/**
 * Repository observation includes system-owned virtual aggregates whose IDs are negative. Those
 * views intentionally return an empty successful EPG window and must never be counted as refreshed
 * sources: doing so can mask failure of every real XMLTV source and suppress WorkManager backoff.
 */
internal fun eligibleEpgRefreshPlaylists(playlists: Iterable<Playlist>): List<Playlist> =
    playlists.filter { playlist -> playlist.id > 0L }
