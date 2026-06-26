package com.iptv.tv

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.WorkManager
import com.iptv.tv.core.domain.repository.DiagnosticsRepository
import com.iptv.tv.core.domain.repository.SettingsRepository
import com.iptv.tv.sync.SyncScheduler
import com.iptv.tv.core.utils.FileLogger
import dagger.Lazy
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

@HiltAndroidApp
class IptvApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var settingsRepository: Lazy<SettingsRepository>

    @Inject
    lateinit var diagnosticsRepository: Lazy<DiagnosticsRepository>

    private val applicationErrorHandler = CoroutineExceptionHandler { _, throwable ->
        if (throwable !is CancellationException) {
            recordApplicationError(
                status = "app_background_coroutine_error",
                threadName = Thread.currentThread().name,
                throwable = throwable,
                blocking = false
            )
        }
    }

    private val applicationScope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + applicationErrorHandler
    )

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordApplicationError(
                status = "app_uncaught_exception",
                threadName = thread.name,
                throwable = throwable,
                blocking = true
            )
            defaultUncaughtExceptionHandler?.uncaughtException(thread, throwable)
        }
        applicationScope.launch(Dispatchers.IO) {
            delay(BACKGROUND_WORK_START_DELAY_MS)
            val workManager = WorkManager.getInstance(this@IptvApp)
            SyncScheduler.schedulePlaylistSync(workManager, repeatHours = 12)
            SyncScheduler.scheduleDownloadQueue(workManager, repeatMinutes = 15)
            SyncScheduler.scheduleRecordingQueue(workManager, repeatMinutes = 15)
            SyncScheduler.scheduleTvHomePublish(workManager, repeatHours = 6)
            val settings = settingsRepository.get()
            settings.observeProviderAutoSyncEnabled()
                .combine(settings.observeProviderAutoSyncIntervalHours()) { enabled, hours ->
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

    override fun onLowMemory() {
        super.onLowMemory()
        recordApplicationEvent(
            status = "app_low_memory",
            message = buildRuntimeContext(prefix = "System reported low memory")
        )
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW_LEVEL) {
            recordApplicationEvent(
                status = "app_trim_memory",
                message = buildRuntimeContext(prefix = "System requested memory trim: level=$level")
            )
        }
    }

    private fun recordApplicationError(
        status: String,
        threadName: String,
        throwable: Throwable,
        blocking: Boolean
    ) {
        val message = buildApplicationErrorMessage(threadName = threadName, throwable = throwable)
        FileLogger.write(this, "ERROR", "Application", "$status | $message", throwable)
        if (blocking) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(FATAL_DIAGNOSTICS_WRITE_TIMEOUT_MS) {
                        diagnosticsRepository.get().addLog(status = status, message = message)
                    }
                }
            }
        } else {
            applicationScope.launch(Dispatchers.IO) {
                runCatching {
                    diagnosticsRepository.get().addLog(status = status, message = message)
                }
            }
        }
    }

    private fun recordApplicationEvent(status: String, message: String) {
        val safeMessage = message.redactSensitiveLogData().take(MAX_DIAGNOSTIC_MESSAGE)
        FileLogger.write(this, "WARN", "Application", "$status | $safeMessage")
        applicationScope.launch(Dispatchers.IO) {
            runCatching {
                diagnosticsRepository.get().addLog(status = status, message = safeMessage)
            }
        }
    }

    private fun buildApplicationErrorMessage(threadName: String, throwable: Throwable): String {
        return buildString {
            append(buildRuntimeContext(prefix = "thread=$threadName"))
            append(" | error=")
            append(throwable.toCauseChain())
            append(" | appFrame=")
            append(throwable.firstAppFrame())
        }.redactSensitiveLogData().take(MAX_DIAGNOSTIC_MESSAGE)
    }

    private fun buildRuntimeContext(prefix: String): String {
        val runtime = Runtime.getRuntime()
        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MB
        val maxMb = runtime.maxMemory() / MB
        val version = runCatching {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName}/${info.longVersionCodeCompat()}"
        }.getOrDefault("unknown")
        return "$prefix | app=$version | sdk=${Build.VERSION.SDK_INT} | device=${Build.MANUFACTURER}/${Build.MODEL} | uptime=${SystemClock.elapsedRealtime() / 1_000}s | mem=${usedMb}MB/${maxMb}MB"
    }

    private fun Throwable.toCauseChain(maxDepth: Int = 6): String {
        val seen = HashSet<Throwable>()
        val parts = ArrayList<String>()
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < maxDepth && seen.add(current)) {
            val message = current.message
                ?.replace(Regex("\\s+"), " ")
                ?.trim()
                .orEmpty()
            parts += if (message.isBlank()) {
                current.javaClass.simpleName
            } else {
                "${current.javaClass.simpleName}: ${message.take(MAX_CAUSE_SEGMENT)}"
            }
            current = current.cause
            depth += 1
        }
        return parts.joinToString(separator = " <- ")
    }

    private fun Throwable.firstAppFrame(): String {
        val frame = stackTrace.firstOrNull { it.className.startsWith(packageName) }
            ?: cause?.stackTrace?.firstOrNull { it.className.startsWith(packageName) }
            ?: stackTrace.firstOrNull()
        return frame?.toString().orEmpty().ifBlank { "unknown" }
    }

    private fun String.redactSensitiveLogData(): String {
        return replace(Regex("(?i)(password|passwd|pass|pwd|token|access_token|refresh_token|api_key|apikey|secret|key|mac|username|login|user)=([^\\s&]+)")) {
            "${it.groupValues[1]}=<redacted>"
        }.replace(Regex("(?i)(://)([^\\s:/?#]+):([^\\s@/?#]+)@")) {
            "${it.groupValues[1]}<redacted>:<redacted>@"
        }
    }

    private fun android.content.pm.PackageInfo.longVersionCodeCompat(): Long {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            longVersionCode
        } else {
            @Suppress("DEPRECATION")
            versionCode.toLong()
        }
    }

    private companion object {
        const val BACKGROUND_WORK_START_DELAY_MS = 12_000L
        const val FATAL_DIAGNOSTICS_WRITE_TIMEOUT_MS = 1_500L
        const val MAX_DIAGNOSTIC_MESSAGE = 3_500
        const val MAX_CAUSE_SEGMENT = 240
        const val MB = 1024 * 1024
        const val TRIM_MEMORY_RUNNING_LOW_LEVEL = 10
    }
}
