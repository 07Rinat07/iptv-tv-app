package com.iptv.tv

import android.app.Activity
import android.app.Application
import android.app.Application.ActivityLifecycleCallbacks
import android.os.Build
import android.os.Bundle
import android.os.Process
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
    private val processStartedAtElapsedMs = SystemClock.elapsedRealtime()
    private var startedActivityCount = 0

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
        val previousForegroundProcess = readPreviousForegroundProcess()
        markProcessForeground(foreground = false)
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
        recordApplicationEvent(
            status = "app_process_start",
            message = buildRuntimeContext(prefix = "Application process started")
        )
        if (previousForegroundProcess != null) {
            recordApplicationEvent(
                status = "app_previous_process_ended_in_foreground",
                message = buildRuntimeContext(
                    prefix = "Previous process ended while UI was foreground: " +
                        "previousPid=${previousForegroundProcess.pid}, " +
                        "markedAt=${previousForegroundProcess.markedAtMillis}"
                )
            )
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

            override fun onActivityStarted(activity: Activity) {
                startedActivityCount += 1
                if (startedActivityCount == 1) markProcessForeground(foreground = true)
            }

            override fun onActivityResumed(activity: Activity) = Unit

            override fun onActivityPaused(activity: Activity) = Unit

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount = (startedActivityCount - 1).coerceAtLeast(0)
                if (startedActivityCount == 0) markProcessForeground(foreground = false)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

            override fun onActivityDestroyed(activity: Activity) = Unit
        })
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
        when {
            level == TRIM_MEMORY_UI_HIDDEN_LEVEL -> recordApplicationEvent(
                status = "app_ui_hidden",
                message = buildRuntimeContext(prefix = "Application UI became hidden: level=$level")
            )
            level in TRIM_MEMORY_RUNNING_LOW_LEVEL..TRIM_MEMORY_RUNNING_CRITICAL_LEVEL ||
                level >= TRIM_MEMORY_BACKGROUND_LEVEL -> recordApplicationEvent(
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

    /**
     * Android 9 does not expose historical process-exit reasons. Persisting the foreground state
     * lets the next process distinguish an ordinary background eviction from a process that
     * disappeared while its UI was visible. This is intentionally diagnostic rather than a claim
     * that every such exit was a Java crash: native aborts, system kills, and force-stop also bypass
     * the default uncaught-exception handler.
     */
    private fun readPreviousForegroundProcess(): ForegroundProcessMarker? {
        val preferences = getSharedPreferences(PROCESS_MARKER_PREFERENCES, MODE_PRIVATE)
        val previousPid = preferences.getInt(PROCESS_MARKER_PID, -1)
        val wasForeground = preferences.getBoolean(PROCESS_MARKER_FOREGROUND, false)
        if (!wasForeground || previousPid <= 0 || previousPid == Process.myPid()) return null
        return ForegroundProcessMarker(
            pid = previousPid,
            markedAtMillis = preferences.getLong(PROCESS_MARKER_TIME, 0L)
        )
    }

    private fun markProcessForeground(foreground: Boolean) {
        // commit() is deliberate: an asynchronous marker can be lost in exactly the abrupt-exit
        // scenario this diagnostic is meant to capture. The payload is only three primitive values.
        getSharedPreferences(PROCESS_MARKER_PREFERENCES, MODE_PRIVATE)
            .edit()
            .putInt(PROCESS_MARKER_PID, Process.myPid())
            .putBoolean(PROCESS_MARKER_FOREGROUND, foreground)
            .putLong(PROCESS_MARKER_TIME, System.currentTimeMillis())
            .commit()
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
        val processUptimeSeconds =
            (SystemClock.elapsedRealtime() - processStartedAtElapsedMs).coerceAtLeast(0L) / 1_000L
        return "$prefix | app=$version | sdk=${Build.VERSION.SDK_INT} | " +
            "device=${Build.MANUFACTURER}/${Build.MODEL} | pid=${Process.myPid()} | " +
            "processUptime=${processUptimeSeconds}s | deviceUptime=${SystemClock.elapsedRealtime() / 1_000}s | " +
            "mem=${usedMb}MB/${maxMb}MB"
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
        const val TRIM_MEMORY_RUNNING_CRITICAL_LEVEL = 15
        const val TRIM_MEMORY_UI_HIDDEN_LEVEL = 20
        const val TRIM_MEMORY_BACKGROUND_LEVEL = 40
        const val PROCESS_MARKER_PREFERENCES = "process_exit_diagnostics"
        const val PROCESS_MARKER_PID = "pid"
        const val PROCESS_MARKER_FOREGROUND = "foreground"
        const val PROCESS_MARKER_TIME = "marked_at"
    }

    private data class ForegroundProcessMarker(
        val pid: Int,
        val markedAtMillis: Long
    )
}
