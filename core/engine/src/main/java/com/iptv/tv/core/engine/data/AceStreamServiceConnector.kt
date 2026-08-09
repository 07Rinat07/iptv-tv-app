package com.iptv.tv.core.engine.data

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
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

    private data class PackageCandidate(
        val packageName: String,
        val versionCode: Long,
        val exposesEngineService: Boolean
    )

    private data class Discovery(
        val selectedPackage: String?,
        val installedPackages: List<String>,
        val servicePackages: List<String>
    )

    private val mutex = Mutex()
    private var service: IAceStreamEngine? = null
    private var bound = false
    private var boundPackageName: String? = null
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
                pendingReady?.completeExceptionally(
                    IllegalStateException("Ace Stream Engine failed to start")
                )
                return
            }
            completeReady(current)
        }
    }

    private val startCallback = object : IStartEngineResponse.Stub() {
        override fun onResult(success: Boolean) {
            val current = service
            if (!success || current == null) {
                pendingReady?.completeExceptionally(
                    IllegalStateException("Ace Stream Engine start request failed")
                )
                return
            }
            completeReady(current)
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            Log.i(TAG, "Ace Stream service connected: $name")
            val current = IAceStreamEngine.Stub.asInterface(binder)
            service = current
            runCatching {
                current.registerCallbackExt(callback, true)
                current.startEngineWithCallback(startCallback)
            }.onFailure { throwable ->
                Log.e(TAG, "Failed to initialize Ace Stream service", throwable)
                pendingReady?.completeExceptionally(throwable)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Log.w(TAG, "Ace Stream service disconnected: $name")
            service = null
            ready = null
            bound = false
            boundPackageName = null
            pendingReady?.completeExceptionally(
                IllegalStateException("Ace Stream Engine service disconnected")
            )
        }

        override fun onBindingDied(name: ComponentName) {
            Log.w(TAG, "Ace Stream service binding died: $name")
            onServiceDisconnected(name)
        }

        override fun onNullBinding(name: ComponentName) {
            Log.e(TAG, "Ace Stream service returned a null binding: $name")
            service = null
            ready = null
            bound = false
            boundPackageName = null
            pendingReady?.completeExceptionally(
                IllegalStateException("Ace Stream Engine returned a null binding")
            )
        }
    }

    suspend fun ensureStarted(timeoutMs: Long = 20_000L): AppResult<Connection> = mutex.withLock {
        ready?.let { return AppResult.Success(it) }

        val deferred = pendingReady?.takeIf { !it.isCompleted }
            ?: CompletableDeferred<Connection>().also { pendingReady = it }

        if (!bound) {
            val discovery = discoverService()
            val packageName = discovery.selectedPackage
            if (packageName == null) {
                pendingReady = null
                return AppResult.Error(
                    "Ace Stream Engine is not installed or is not visible to the application"
                )
            }

            val intent = Intent(SERVICE_ACTION).setPackage(packageName)
            boundPackageName = packageName
            val bindResult = runCatching {
                context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
            }

            val bindError = bindResult.exceptionOrNull()
            if (bindError != null) {
                Log.e(TAG, "Cannot bind to Ace Stream package=$packageName", bindError)
                boundPackageName = null
                pendingReady = null
                return AppResult.Error(
                    "Cannot bind to Ace Stream Engine service in package $packageName: " +
                        (bindError.message ?: bindError.javaClass.simpleName),
                    bindError
                )
            }

            bound = bindResult.getOrDefault(false)
            if (!bound) {
                Log.e(
                    TAG,
                    "bindService returned false: package=$packageName, " +
                        "installed=${discovery.installedPackages}, services=${discovery.servicePackages}"
                )
                boundPackageName = null
                pendingReady = null
                return AppResult.Error(
                    "Ace Stream package $packageName is installed, but its Engine service is unavailable"
                )
            }
        } else {
            service?.let { current ->
                runCatching { current.startEngineWithCallback(startCallback) }
                    .onFailure { throwable ->
                        Log.e(TAG, "Failed to restart Ace Stream Engine", throwable)
                        deferred.completeExceptionally(throwable)
                    }
            }
        }

        return runCatching { withTimeout(timeoutMs) { deferred.await() } }
            .fold(
                onSuccess = {
                    pendingReady = null
                    AppResult.Success(it)
                },
                onFailure = { throwable ->
                    Log.e(TAG, "Ace Stream Engine startup failed", throwable)
                    resetBinding()
                    AppResult.Error(
                        "Ace Stream Engine startup failed: " +
                            (throwable.message ?: throwable.javaClass.simpleName),
                        throwable
                    )
                }
            )
    }

    fun close() {
        resetBinding()
    }

    private fun resetBinding() {
        val current = service
        if (current != null) {
            runCatching { current.unregisterCallback(callback) }
        }
        if (bound) {
            runCatching { context.unbindService(serviceConnection) }
        }
        service = null
        ready = null
        pendingReady = null
        bound = false
        boundPackageName = null
    }

    private fun completeReady(current: IAceStreamEngine) {
        runCatching {
            val enginePort = current.engineApiPort
            val httpPort = current.httpApiPort
            require(enginePort > 0) { "Invalid Engine API port: $enginePort" }
            require(httpPort > 0) { "Invalid HTTP API port: $httpPort" }
            Connection(
                packageName = boundPackageName ?: "unknown",
                engineApiPort = enginePort,
                httpApiPort = httpPort,
                accessToken = current.accessToken
            )
        }.onSuccess { connection ->
            Log.i(
                TAG,
                "Ace Stream Engine ready: package=${connection.packageName}, " +
                    "enginePort=${connection.engineApiPort}, httpPort=${connection.httpApiPort}"
            )
            ready = connection
            pendingReady?.complete(connection)
        }.onFailure { throwable ->
            Log.e(TAG, "Ace Stream Engine reported invalid connection data", throwable)
            pendingReady?.completeExceptionally(throwable)
        }
    }

    @Suppress("DEPRECATION")
    private fun discoverService(): Discovery {
        val pm = context.packageManager

        val installedKnown = KNOWN_PACKAGES.mapNotNull { packageName ->
            packageVersion(pm, packageName)?.let { version ->
                PackageCandidate(
                    packageName = packageName,
                    versionCode = version,
                    exposesEngineService = false
                )
            }
        }

        val resolvedServices = runCatching {
            pm.queryIntentServices(
                Intent(SERVICE_ACTION),
                PackageManager.MATCH_ALL
            )
        }.onFailure { throwable ->
            Log.w(TAG, "Failed to query Ace Stream services", throwable)
        }.getOrDefault(emptyList()).mapNotNull { resolveInfo ->
            val packageName = resolveInfo.serviceInfo?.packageName ?: return@mapNotNull null
            PackageCandidate(
                packageName = packageName,
                versionCode = packageVersion(pm, packageName) ?: 0L,
                exposesEngineService = true
            )
        }

        val candidates = (installedKnown + resolvedServices)
            .groupBy { it.packageName }
            .map { (packageName, entries) ->
                PackageCandidate(
                    packageName = packageName,
                    versionCode = entries.maxOf { it.versionCode },
                    exposesEngineService = entries.any { it.exposesEngineService }
                )
            }

        val selected = candidates.sortedWith(
            compareByDescending<PackageCandidate> { it.exposesEngineService }
                .thenByDescending { it.versionCode }
        ).firstOrNull()?.packageName

        val discovery = Discovery(
            selectedPackage = selected,
            installedPackages = installedKnown.map { it.packageName },
            servicePackages = resolvedServices.map { it.packageName }.distinct()
        )

        Log.i(
            TAG,
            "Ace Stream discovery: installed=${discovery.installedPackages}, " +
                "services=${discovery.servicePackages}, selected=${discovery.selectedPackage}"
        )
        return discovery
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(pm: PackageManager, packageName: String): Long? =
        runCatching {
            pm.getPackageInfo(packageName, 0).versionCode.toLong()
        }.getOrNull()

    private companion object {
        const val TAG = "AceStreamConnector"
        const val SERVICE_ACTION = "org.acestream.engine.service.v0.IAceStreamEngine"
        val KNOWN_PACKAGES = listOf(
            "org.acestream.node",
            "org.acestream.live",
            "org.acestream.media",
            "org.acestream.media.atv",
            "org.acestream.core",
            "org.acestream.core.atv"
        )
    }
}
