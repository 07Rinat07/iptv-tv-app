package com.iptv.tv.core.p2p

import android.content.Context
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/** Lifecycle bridge between a real runtime-owned TCP listener and its BEP-14 session. */
internal object AceLiveLsdRuntimeRegistry {
    private val lock = Any()
    private var applicationContext: Context? = null
    private val registeredPorts = linkedSetOf<Int>()
    private val sessions = LinkedHashMap<Int, RuntimeSession>()

    fun install(context: Context) = synchronized(lock) {
        if (applicationContext == null) applicationContext = context.applicationContext
    }

    fun registerListener(port: Int): Closeable {
        require(port in 1..65535)
        synchronized(lock) { registeredPorts += port }
        return Closeable { unregisterListener(port) }
    }

    fun startOrSnapshot(
        swarmKey: AceLiveSwarmKey,
        announcePort: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): List<AceLiveTcpPeerEndpoint> {
        require(announcePort in 1..65535)
        require(nowMillis >= 0L)
        val session = synchronized(lock) {
            if (announcePort !in registeredPorts) return emptyList()
            val existing = sessions[announcePort]
            if (existing != null && existing.swarmKey == swarmKey) {
                existing
            } else {
                existing?.close()
                val context = applicationContext ?: return emptyList()
                RuntimeSession(context, swarmKey, announcePort).also { sessions[announcePort] = it }
            }
        }
        return session.cache.snapshot(nowMillis)
    }

    private fun unregisterListener(port: Int) {
        val session = synchronized(lock) {
            registeredPorts -= port
            sessions.remove(port)
        }
        session?.close()
    }

    internal fun resetForTests() {
        val toClose = synchronized(lock) {
            registeredPorts.clear()
            sessions.values.toList().also { sessions.clear() }
        }
        toClose.forEach(RuntimeSession::close)
    }

    private class RuntimeSession(
        context: Context,
        val swarmKey: AceLiveSwarmKey,
        announcePort: Int
    ) : Closeable {
        val cache = AceLiveLsdPeerCache()
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val discoveryLease = AceLiveLocalServiceDiscovery(
            lanResolver = AndroidAceLiveLsdLanResolver(context),
            multicastLeaseFactory = AndroidAceLiveLsdMulticastLeaseFactory(context)
        ).start(
            scope = scope,
            request = AceLiveLsdRequest(swarmKey, announcePort),
            onPeer = { endpoint -> cache.record(endpoint, System.currentTimeMillis()) }
        )
        private val closed = AtomicBoolean(false)

        override fun close() {
            if (!closed.compareAndSet(false, true)) return
            discoveryLease.close()
            scope.cancel()
        }
    }
}
