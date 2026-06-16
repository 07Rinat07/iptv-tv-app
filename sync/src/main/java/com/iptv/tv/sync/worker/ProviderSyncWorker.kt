package com.iptv.tv.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.ProviderAccountRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ProviderSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val providerAccountRepository: ProviderAccountRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        diagnosticsRepository.addLog(
            status = "provider_sync_start",
            message = "Provider account auto-sync started"
        )

        return when (val result = providerAccountRepository.syncAllProviders()) {
            is AppResult.Success -> {
                diagnosticsRepository.addLog(
                    status = "provider_sync_ok",
                    message = "Provider account auto-sync completed, synced=${result.data}"
                )
                Result.success(
                    Data.Builder()
                        .putInt(KEY_SYNCED_COUNT, result.data)
                        .build()
                )
            }
            is AppResult.Error -> {
                diagnosticsRepository.addLog(
                    status = "provider_sync_error",
                    message = result.message
                )
                Result.retry()
            }
            AppResult.Loading -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "provider_account_sync"
        const val KEY_SYNCED_COUNT = "synced_count"
    }
}
