package com.iptv.tv

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.iptv.tv.sync.SyncScheduler
import com.iptv.tv.core.utils.FileLogger
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class IptvApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

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
    }
}
