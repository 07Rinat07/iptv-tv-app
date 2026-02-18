package com.iptv.tv.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.iptv.tv.sync.worker.DownloadQueueWorker
import com.iptv.tv.sync.worker.PlaylistSyncWorker
import kotlin.math.abs
import java.util.concurrent.TimeUnit

object SyncScheduler {
    fun schedulePlaylistSync(workManager: WorkManager, repeatHours: Int) {
        val normalizedHours = normalizeSyncHours(repeatHours)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            normalizedHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInitialDelay(5, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(
            PlaylistSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    internal fun normalizeSyncHours(repeatHours: Int): Int {
        val allowed = listOf(6, 12, 24)
        if (repeatHours <= 0) return 12
        return allowed.minBy { allowedValue -> abs(allowedValue - repeatHours) }
    }

    fun scheduleDownloadQueue(workManager: WorkManager, repeatMinutes: Long = 15L) {
        val request = PeriodicWorkRequestBuilder<DownloadQueueWorker>(
            repeatMinutes.coerceIn(15L, 60L),
            TimeUnit.MINUTES
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DownloadQueueWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
