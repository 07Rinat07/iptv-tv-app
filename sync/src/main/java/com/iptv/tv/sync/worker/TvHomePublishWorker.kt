package com.iptv.tv.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.TvHomeIntegrationRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class TvHomePublishWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val tvHomeIntegrationRepository: TvHomeIntegrationRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        var published = 0
        val failures = mutableListOf<String>()

        listOf(
            "recent" to suspend { tvHomeIntegrationRepository.publishRecentChannels() },
            "favorites" to suspend { tvHomeIntegrationRepository.publishFavorites() },
            "recordings" to suspend { tvHomeIntegrationRepository.publishRecordings() },
            "watch_next" to suspend { tvHomeIntegrationRepository.publishWatchNext() }
        ).forEach { (name, action) ->
            when (val result = action()) {
                is AppResult.Success -> published += result.data
                is AppResult.Error -> failures += "$name=${result.message}"
                AppResult.Loading -> Unit
            }
        }

        return if (failures.isEmpty()) {
            diagnosticsRepository.addLog(
                status = "tv_home_publish_ok",
                message = "Published Android TV Home items=$published"
            )
            Result.success(
                Data.Builder()
                    .putInt(KEY_PUBLISHED, published)
                    .build()
            )
        } else {
            diagnosticsRepository.addLog(
                status = "tv_home_publish_partial",
                message = failures.joinToString("; ").take(500)
            )
            Result.success(
                Data.Builder()
                    .putInt(KEY_PUBLISHED, published)
                    .putString(KEY_FAILURES, failures.joinToString("; ").take(500))
                    .build()
            )
        }
    }

    companion object {
        const val WORK_NAME = "tv_home_publish"
        const val KEY_PUBLISHED = "published"
        const val KEY_FAILURES = "failures"
    }
}
