package com.iptv.tv.sync.worker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.DownloadRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class DownloadQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val downloadRepository: DownloadRepository,
    private val diagnosticsRepository: DiagnosticsRepository,
    private val settingsRepository: SettingsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val wifiOnly = settingsRepository.observeDownloadsWifiOnly().first()
        val maxConcurrent = settingsRepository.observeMaxParallelDownloads().first()
            .coerceIn(1, 5)

        if (wifiOnly && !isWifiOrEthernetConnected()) {
            diagnosticsRepository.addLog(
                status = "download_queue_skip",
                message = "Download worker skipped: wifi-only enabled and no wifi/ethernet connection"
            )
            return Result.success(
                Data.Builder()
                    .putInt(KEY_PROCESSED, 0)
                    .build()
            )
        }

        return when (val result = downloadRepository.tickQueue(maxConcurrent = maxConcurrent)) {
            is AppResult.Success -> {
                diagnosticsRepository.addLog(
                    status = "download_queue_ok",
                    message = "Download queue worker processed=${result.data}, maxConcurrent=$maxConcurrent"
                )
                Result.success(
                    Data.Builder()
                        .putInt(KEY_PROCESSED, result.data)
                        .build()
                )
            }
            is AppResult.Error -> {
                diagnosticsRepository.addLog(
                    status = "download_queue_error",
                    message = result.message
                )
                Result.retry()
            }
            AppResult.Loading -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "download_queue_tick"
        const val KEY_PROCESSED = "processed"
    }

    private fun isWifiOrEthernetConnected(): Boolean {
        val connectivity = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val active = connectivity.activeNetwork ?: return false
        val caps = connectivity.getNetworkCapabilities(active) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }
}
