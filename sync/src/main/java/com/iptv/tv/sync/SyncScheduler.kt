package com.iptv.tv.sync

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.iptv.tv.core.model.EpgSettingsPolicy
import com.iptv.tv.sync.worker.DownloadQueueWorker
import com.iptv.tv.sync.worker.EpgRefreshWorker
import com.iptv.tv.sync.worker.PlaylistSyncWorker
import com.iptv.tv.sync.worker.ProviderSyncWorker
import com.iptv.tv.sync.worker.RecordingQueueWorker
import com.iptv.tv.sync.worker.TvHomePublishWorker
import java.util.concurrent.TimeUnit
import kotlin.math.abs

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

    fun scheduleEpgRefresh(
        workManager: WorkManager,
        repeatHours: Int = EpgSettingsPolicy.DEFAULT_REFRESH_INTERVAL_HOURS
    ) {
        val normalizedHours = EpgSettingsPolicy.normalizeRefreshIntervalHours(repeatHours)
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
        val request = PeriodicWorkRequestBuilder<EpgRefreshWorker>(
            normalizedHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            EpgRefreshWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelEpgRefresh(workManager: WorkManager) {
        workManager.cancelUniqueWork(EpgRefreshWorker.WORK_NAME)
    }

    /**
     * Startup refreshes are freshness-gated again inside the worker so a periodic run winning the
     * race does not cause a second large XMLTV pass. A future explicit user action should set
     * [force] to true so "Обновить EPG" always performs the requested refresh.
     */
    fun requestEpgRefresh(workManager: WorkManager, force: Boolean = false) {
        val request = OneTimeWorkRequestBuilder<EpgRefreshWorker>()
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInputData(workDataOf(EpgRefreshWorker.KEY_FORCE_REFRESH to force))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniqueWork(
            EpgRefreshWorker.IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request
        )
    }

    fun scheduleProviderSync(workManager: WorkManager, repeatHours: Int = 12) {
        val normalizedHours = normalizeSyncHours(repeatHours)
        val request = PeriodicWorkRequestBuilder<ProviderSyncWorker>(
            normalizedHours.toLong(),
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInitialDelay(10, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            ProviderSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun cancelProviderSync(workManager: WorkManager) {
        workManager.cancelUniqueWork(ProviderSyncWorker.WORK_NAME)
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

    fun scheduleRecordingQueue(workManager: WorkManager, repeatMinutes: Long = 15L) {
        val request = PeriodicWorkRequestBuilder<RecordingQueueWorker>(
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
            RecordingQueueWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    fun scheduleTvHomePublish(workManager: WorkManager, repeatHours: Long = 6L) {
        val request = PeriodicWorkRequestBuilder<TvHomePublishWorker>(
            repeatHours.coerceIn(6L, 24L),
            TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiresBatteryNotLow(true)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.MINUTES)
            .setInitialDelay(2, TimeUnit.MINUTES)
            .build()

        workManager.enqueueUniquePeriodicWork(
            TvHomePublishWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }
}
