package com.iptv.tv.core.p2p

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * Installs only the process-local Android context required to discover the physical LAN gateway.
 * The provider performs no network I/O during application startup; mapping begins only after the
 * live Ace runtime has bound its real inbound TCP listener.
 */
internal class AceLivePortMappingInitializer : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.applicationContext?.let(AceLivePortMappingRuntime::install)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}

/** Process-scoped factory; every live listener still owns and closes its own finite mapping lease. */
internal object AceLivePortMappingRuntime {
    private val applicationContext = AtomicReference<Context?>(null)
    private val httpClient by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        OkHttpClient.Builder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
    }

    fun install(context: Context) {
        applicationContext.compareAndSet(null, context.applicationContext)
    }

    fun start(internalPort: Int): Closeable? {
        val context = applicationContext.get() ?: return null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val session = runCatching {
            AceLivePortMappingCoordinator.default(context, httpClient).start(
                scope = scope,
                internalPort = internalPort
            )
        }.getOrElse {
            scope.cancel()
            return null
        }
        return RuntimeMappingLease(scope, session)
    }

    private class RuntimeMappingLease(
        private val scope: CoroutineScope,
        private val session: AceLivePortMappingSession
    ) : Closeable {
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            scope.launch {
                try {
                    session.close()
                } finally {
                    scope.cancel()
                }
            }
        }
    }
}
