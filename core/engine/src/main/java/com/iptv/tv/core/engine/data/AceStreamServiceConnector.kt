package com.iptv.tv.core.engine.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.iptv.tv.core.common.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.acestream.engine.service.v0.IAceStreamEngine
import org.acestream.engine.service.v0.IAceStreamEngineCallback
import org.acestream.engine.service.v0.IStartEngineResponse
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AceStreamServiceConnector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    data class Connection(
        val packageName: String,
        val engineApiPort: Int,
        val httpApiPort: Int,
        val accessToken: String?
    ) {
        val endpoint: String
            get() = "http://127.0.0.1:$httpApiPort"
    }

    private val mutex = Mutex()
    private var service: IAceStreamEngine? = null
    private var bound = false
    private var ready: Connection? = null
    private var pendingReady: CompletableDeferred<Connection>? = null

    private val callback = object : IAceStreamEngineCallback.Stub() {
        override fun onUnpacking() = Unit
        override fun onStarting() = Unit
        override fun onStopped() {
            ready = null
        }
        override fun onWaitForNetworkConnection() = Unit
        override fun onPlaylistUpdated() = Unit
        override fun onEPGUpdated() = Unit
        override fun onRestartPlayer() = Unit
        override fun onSettingsUpdated() = Unit
        override fun onAuthUpdated() = Unit

        override fun onReady(listenPort: Int) {
            val current = service
            if (listenPort <= 0 || current == null) {
                pendingReady?.completeExceptionally(IllegalStateException("Ace Stream Engine failed to start"))
                return
            }
            completeReady(current)
        }
    }

    private val startCallback = object : IStartEngineResponse.Stub() {
        override fun onResult(success: Boolean) {
            val current = service
            if (!success || current == null) {
                pendingReady?.completeExceptionally(IllegalStateException("Ace Stream Engine start request failed"))
                return
            }
            completeReady(current)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val current = IAceStreamEngine.Stub.asInterface(binder)
            service = current
            runCatching {
                current.registerCallbackExt(callback, true)
                current.startEngineWithCallback(startCallback)
            }.onFailure { throwable ->
                pendingReady?.completeExceptionally(throwable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            ready = null
            bound = false
            pendingReady?.completeExceptionally(IllegalStateException("Ace Stream Engine service disconnected"))
        }

        override fun onBindingDied(name: ComponentName) {
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            pendingReady?.completeExceptionally(IllegalStateException("Ace Stream Engine returned a null binding"))
        }
    }

    suspend fun ensureStarted(timeoutMs: Long = 20_000L): AppResult<Connection> = mutex.withLock {
        ready?.let { return AppResult.Success(it) }

        val deferred = pendingReady?.takeIf { !it.isCompleted } ?: CompletableDeferred<Connection>().also {
            pendingReady = it
        }

        if (!bound) {
            val packageName = selectServicePackage()
                ?: return AppResult.Error("Ace Stream Engine is not installed")
            val intent = Intent(SERVICE_ACTION).setPackage(packageName)
            bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            if (!bound) {
                pendingReady = null
                return AppResult.Error("Cannot bind to Ace Stream Engine service")
            }
        } else {
            service?.let { current ->
                runCatching { current.startEngineWithCallback(startCallback) }
                    .onFailure { deferred.completeExceptionally(it) }
            }
        }

        return runCatching { withTimeout(timeoutMs) { deferred.await() } }
            .fold(
                onSuccess = { AppResult.Success(it) },
                onFailure = { AppResult.Error("Ace Stream Engine startup failed: ${it.message}", it) }
            )
    }

    fun close() {
        val current = service
        if (current != null) runCatching { current.unregisterCallback(callback) }
        if (bound) runCatching { context.unbindService(serviceConnection) }
        service = null
        ready = null
        pendingReady = null
        bound = false
    }

    private fun completeReady(current: IAceStreamEngine) {
        runCatching {
            val enginePort = current.engineApiPort
            val httpPort = current.httpApiPort
            require(enginePort > 0) { "Invalid Engine API port: $enginePort" }
            require(httpPort > 0) { "Invalid HTTP API port: $httpPort" }
            val packageName = selectServicePackage() ?: "unknown"
            Connection(packageName, enginePort, httpPort, current.accessToken)
        }.onSuccess { connection ->
            ready = connection
            pendingReady?.complete(connection)
        }.onFailure { throwable ->
            pendingReady?.completeExceptionally(throwable)
        }
    }

    @Suppress("DEPRECATION")
    private fun selectServicePackage(): String? {
        val pm = context.packageManager
        val known = KNOWN_PACKAGES.mapNotNull { packageName ->
            runCatching {
                val info = pm.getPackageInfo(packageName, 0)
                packageName to info.versionCode.toLong()
            }.getOrNull()
        }

        val discovered = pm.queryIntentServices(
            Intent(SERVICE_ACTION),
            PackageManager.MATCH_DEFAULT_ONLY
        ).mapNotNull { resolveInfo ->
            val packageName = resolveInfo.serviceInfo?.packageName ?: return@mapNotNull null
            runCatching {
                val info = pm.getPackageInfo(packageName, 0)
                packageName to info.versionCode.toLong()
            }.getOrNull()
        }

        return (known + discovered)
            .distinctBy { it.first }
            .maxByOrNull { it.second }
            ?.first
    }

    private companion object {
        const val SERVICE_ACTION = "org.acestream.engine.service.v0.IAceStreamEngine"
        val KNOWN_PACKAGES = listOf(
            "org.acestream.media",
            "org.acestream.media.atv",
            "org.acestream.core",
            "org.acestream.core.atv"
        )
    }
}
