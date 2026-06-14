package com.iptv.tv.sync.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import com.iptv.tv.core.common.AppResult
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.RecordingRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class RecordingQueueWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val recordingRepository: RecordingRepository,
    private val diagnosticsRepository: DiagnosticsRepository
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        return when (val result = recordingRepository.processDueRecordings(maxConcurrent = 1)) {
            is AppResult.Success -> {
                val cleanup = recordingRepository.cleanupOldRecordings(maxAgeDays = 30)
                val cleanupCount = (cleanup as? AppResult.Success<Int>)?.data ?: 0
                diagnosticsRepository.addLog(
                    status = "recording_queue_ok",
                    message = "Recording queue worker processed=${result.data}, cleanup=$cleanupCount"
                )
                Result.success(
                    Data.Builder()
                        .putInt(KEY_PROCESSED, result.data)
                        .putInt(KEY_CLEANED, cleanupCount)
                        .build()
                )
            }
            is AppResult.Error -> {
                diagnosticsRepository.addLog(
                    status = "recording_queue_error",
                    message = result.message
                )
                Result.retry()
            }
            AppResult.Loading -> Result.retry()
        }
    }

    companion object {
        const val WORK_NAME = "recording_queue_tick"
        const val KEY_PROCESSED = "processed"
        const val KEY_CLEANED = "cleaned"
    }
}
