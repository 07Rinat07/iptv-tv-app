package com.iptv.tv.core.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-wide speculative DHT acquisition used only to overlap tracker startup with DHT discovery.
 *
 * The registry owns at most one bounded DHT job. A new swarm cancels the previous speculative job so
 * rapid channel switching cannot leave an obsolete walk occupying the process-wide DHT gate. The
 * same swarm may reuse one in-flight or just-completed acquisition for a short period; this retention
 * is a result-reuse lifetime, not a network timeout and does not widen any discovery budget.
 */
internal object AceLiveBackgroundDhtAcquisitionRegistry {
    private const val RETENTION_MILLIS = 20_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private var current: Lease? = null

    fun startOrReuse(
        swarmKey: AceLiveSwarmKey,
        nowMillis: Long = System.currentTimeMillis(),
        runner: suspend () -> Outcome
    ): Lease = synchronized(lock) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        val key = swarmKey.toHex()
        current?.let { existing ->
            if (existing.swarmKeyHex == key && !isExpired(existing, nowMillis)) {
                return@synchronized existing
            }
            existing.deferred.cancel()
            current = null
        }

        val lease = Lease(
            swarmKeyHex = key,
            startedAtMillis = nowMillis,
            deferred = scope.async { runner() }
        )
        current = lease
        lease
    }

    fun acquire(
        swarmKey: AceLiveSwarmKey,
        nowMillis: Long = System.currentTimeMillis()
    ): Lease? = synchronized(lock) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }
        val existing = current ?: return@synchronized null
        if (existing.swarmKeyHex != swarmKey.toHex()) return@synchronized null
        if (isExpired(existing, nowMillis)) {
            existing.deferred.cancel()
            current = null
            return@synchronized null
        }
        existing
    }

    fun release(lease: Lease) = synchronized(lock) {
        if (current === lease) current = null
    }

    fun resetForTests() = synchronized(lock) {
        current?.deferred?.cancel()
        current = null
    }

    private fun isExpired(lease: Lease, nowMillis: Long): Boolean {
        val age = if (nowMillis >= lease.startedAtMillis) {
            nowMillis - lease.startedAtMillis
        } else {
            Long.MAX_VALUE
        }
        return age > RETENTION_MILLIS
    }

    internal class Lease(
        val swarmKeyHex: String,
        val startedAtMillis: Long,
        val deferred: Deferred<Outcome>
    )

    internal sealed interface Outcome {
        data object NotRequested : Outcome
        data object Failed : Outcome
        data class Success(val value: AceLiveDhtDiscoveryResult) : Outcome
    }
}
