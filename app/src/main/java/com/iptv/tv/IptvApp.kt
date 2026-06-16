package com.iptv.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.sync.SyncScheduler
import com.iptv.tv.core.utils.FileLogger
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class IptvApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                FileLogger.write(this, "FATAL", "Uncaught", "Uncaught exception in thread ${thread.name}", throwable)
            } catch (ignored: Exception) {}
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
        val workManager = WorkManager.getInstance(this)
        SyncScheduler.schedulePlaylistSync(workManager, repeatHours = 12)
        SyncScheduler.scheduleDownloadQueue(workManager, repeatMinutes = 15)
        SyncScheduler.scheduleRecordingQueue(workManager, repeatMinutes = 15)
        SyncScheduler.scheduleTvHomePublish(workManager, repeatHours = 6)
        applicationScope.launch {
            settingsRepository.observeProviderAutoSyncEnabled()
                .combine(settingsRepository.observeProviderAutoSyncIntervalHours()) { enabled, hours ->
                    enabled to hours
                }
                .distinctUntilChanged()
                .collect { (enabled, hours) ->
                    if (enabled) {
                        SyncScheduler.scheduleProviderSync(workManager, repeatHours = hours)
                    } else {
                        SyncScheduler.cancelProviderSync(workManager)
                    }
                }
        }
    }
}
