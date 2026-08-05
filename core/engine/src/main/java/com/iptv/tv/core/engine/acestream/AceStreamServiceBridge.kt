package com.iptv.tv.core.engine.acestream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.IBinder
import android.os.RemoteException
import com.iptv.tv.core.common.AppResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.acestream.engine.service.v0.IAceStreamEngine
import org.acestream.engine.service.v0.IAceStreamEngineCallback
import org.acestream.engine.service.v0.IStartEngineResponse
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Connects to an installed official Ace Stream application through its public
 * Android Service/AIDL contract. The engine process stays in the Ace Stream
 * application; this app only receives the local HTTP endpoint used by Media3.
 */
@Singleton
class AceStreamServiceBridge @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext
    private val lock = Any()

    @Volatile
    private var service: IAceStreamEngine? = null

    @Volatile
    private var bound = false

    @Volatile
    private var endpoint: AceStreamServiceEndpoint? = null

    private var pendingStart: CompletableDeferred<AppResult<AceStreamServiceEndpoint>>? = null

    private val engineCallback = object : IAceStreamEngineCallback.Stub() {
        override fun onUnpacking() = Unit
        override fun onStarting() = Unit
        override fun onWaitForNetworkConnection() = Unit
        override fun onPlaylistUpdated() = Unit
        override fun onEPGUpdated() = Unit
        override fun onRestartPlayer() = Unit
        override fun onSettingsUpdated() = Unit
        override fun onAuthUpdated() = Unit

        override fun onReady(listenPort: Int) {
            if (listenPort < 0) {
                completeStart(AppResult.Error("Ace Stream Engine failed to start"))
                return
            }
            completeFromService()
        }

        override fun onStopped() {
            endpoint = null
        }
    }

    private val startResponse = object : IStartEngineResponse.Stub() {
        override fun onResult(success: Boolean) {
            if (success) {
                completeFromService()
            } else {
                completeStart(AppResult.Error("Ace Stream Engine rejected the start request"))
            }
        }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val connectedService = IAceStreamEngine.Stub.asInterface(binder)
            service = connectedService
            runCatching {
                connectedService.registerCallbackExt(engineCallback, true)
                connectedService.startEngineWithCallback(startResponse)
            }.onFailure { throwable ->
                completeStart(
                    AppResult.Error(
                        "Unable to initialize Ace Stream Engine service: ${throwable.message}",
                        throwable
                    )
                )
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            endpoint = null
            bound = false
            completeStart(AppResult.Error("Ace Stream Engine service disconnected"))
        }

        override fun onBindingDied(name: ComponentName) {
            service = null
            endpoint = null
            bound = false
            completeStart(AppResult.Error("Ace Stream Engine service binding died"))
        }

        override fun onNullBinding(name: ComponentName) {
            service = null
            endpoint = null
            bound = false
            completeStart(AppResult.Error("Ace Stream Engine returned an empty service binding"))
        }
    }

    fun currentEndpoint(): AceStreamServiceEndpoint? = endpoint

    suspend fun startEngine(timeoutMs: Long = DEFAULT_START_TIMEOUT_MS): AppResult<AceStreamServiceEndpoint> {
        endpoint?.let { return AppResult.Success(it) }

        val (deferred, shouldBind) = synchronized(lock) {
            val active = pendingStart?.takeUnless { it.isCompleted }
            if (active != null) {
                active to false
            } else {
                CompletableDeferred<AppResult<AceStreamServiceEndpoint>>().also {
                    pendingStart = it
                } to true
            }
        }

        if (shouldBind) {
            withContext(Dispatchers.Main.immediate) {
                bindOrStart()
            }
        }

        return withTimeoutOrNull(timeoutMs) { deferred.await() }
            ?: AppResult.Error("Timed out while starting Ace Stream Engine")
    }

    fun release() {
        val connectedService = service
        if (connectedService != null) {
            runCatching { connectedService.unregisterCallback(engineCallback) }
        }
        if (bound) {
            runCatching { appContext.unbindService(connection) }
        }
        service = null
        endpoint = null
        bound = false
        synchronized(lock) {
            pendingStart?.cancel()
            pendingStart = null
        }
    }

    private fun bindOrStart() {
        val connectedService = service
        if (connectedService != null) {
            runCatching { connectedService.startEngineWithCallback(startResponse) }
                .onFailure { throwable ->
                    completeStart(
                        AppResult.Error(
                            "Unable to start Ace Stream Engine: ${throwable.message}",
                            throwable
                        )
                    )
                }
            return
        }

        val serviceIntent = findServiceIntent()
        if (serviceIntent == null) {
            completeStart(
                AppResult.Error(
                    "Ace Stream Engine is not installed. Install the official Ace Stream app for Android/Android TV."
                )
            )
            return
        }

        bound = runCatching {
            appContext.bindService(serviceIntent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { throwable ->
            completeStart(
                AppResult.Error(
                    "Unable to bind Ace Stream Engine service: ${throwable.message}",
                    throwable
                )
            )
            false
        }

        if (!bound) {
            completeStart(AppResult.Error("Ace Stream Engine service was found but binding failed"))
        }
    }

    private fun completeFromService() {
        val connectedService = service
        if (connectedService == null) {
            completeStart(AppResult.Error("Ace Stream Engine service is unavailable"))
            return
        }

        val resolved = try {
            val engineApiPort = connectedService.engineApiPort
            val httpApiPort = connectedService.httpApiPort
            val accessToken = connectedService.accessToken.orEmpty()
            val playbackPort = httpApiPort.takeIf { it > 0 } ?: engineApiPort
            if (playbackPort <= 0) {
                throw IllegalStateException("Engine returned invalid HTTP/API ports")
            }
            AceStreamServiceEndpoint(
                endpointUrl = "http://127.0.0.1:$playbackPort",
                engineApiPort = engineApiPort,
                httpApiPort = httpApiPort,
                accessToken = accessToken
            )
        } catch (throwable: Throwable) {
            completeStart(
                AppResult.Error(
                    "Unable to read Ace Stream Engine endpoint: ${throwable.message}",
                    throwable
                )
            )
            return
        }

        endpoint = resolved
        completeStart(AppResult.Success(resolved))
    }

    private fun completeStart(result: AppResult<AceStreamServiceEndpoint>) {
        synchronized(lock) {
            val deferred = pendingStart
            if (deferred != null && !deferred.isCompleted) {
                deferred.complete(result)
            }
            pendingStart = null
        }
    }

    private fun findServiceIntent(): Intent? {
        val packageManager = appContext.packageManager
        val candidates = linkedMapOf<String, Long>()

        KNOWN_PACKAGES.forEach { packageName ->
            packageVersion(packageManager, packageName)?.let { version ->
                candidates[packageName] = version
            }
        }

        queryEngineServices(packageManager).forEach { resolveInfo ->
            val packageName = resolveInfo.serviceInfo?.packageName ?: return@forEach
            packageVersion(packageManager, packageName)?.let { version ->
                candidates[packageName] = maxOf(candidates[packageName] ?: Long.MIN_VALUE, version)
            }
        }

        val selectedPackage = candidates.maxByOrNull { it.value }?.key ?: return null
        return Intent(ENGINE_SERVICE_ACTION).setPackage(selectedPackage)
    }

    @Suppress("DEPRECATION")
    private fun packageVersion(packageManager: PackageManager, packageName: String): Long? {
        return try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                packageManager.getPackageInfo(packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                info.versionCode.toLong()
            }
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun queryEngineServices(packageManager: PackageManager): List<ResolveInfo> {
        val intent = Intent(ENGINE_SERVICE_ACTION)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentServices(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            packageManager.queryIntentServices(intent, PackageManager.MATCH_DEFAULT_ONLY)
        }
    }

    companion object {
        const val ENGINE_SERVICE_ACTION = "org.acestream.engine.service.v0.IAceStreamEngine"
        const val DEFAULT_START_TIMEOUT_MS = 20_000L

        val KNOWN_PACKAGES = listOf(
            "org.acestream.media",
            "org.acestream.media.atv",
            "org.acestream.core",
            "org.acestream.core.atv"
        )
    }
}

data class AceStreamServiceEndpoint(
    val endpointUrl: String,
    val engineApiPort: Int,
    val httpApiPort: Int,
    val accessToken: String
)
