package com.iptv.tv.core.p2p

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async

/**
 * Process-wide speculative DHT acquisition used only to overlap tracker startup with DHT discovery.
 *
 * The latest swarm may reuse one in-flight or just-completed acquisition for a short period. A
 * different swarm replaces only the reuse slot: it must not blindly cancel the previous Deferred,
 * because that acquisition may already have become the synchronous weak-tracker fallback in another
 * orchestrator. All memory-heavy network work remains serialized by the existing process-wide DHT
 * execution mutex. Cross-swarm cancellation belongs to the runtime/generation owner that can prove a
 * lookup is obsolete, not to this process-global reuse registry.
 *
 * The retention lifetime below is result reuse only. It is not a network timeout and does not widen
 * any DHT discovery budget.
 */
internal object AceLiveBackgroundDhtAcquisitionRegistry {
    private const val RETENTION_MILLIS = 20_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Any()
    private val activeLeases = linkedSetOf<Lease>()
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
            if (isExpired(existing, nowMillis)) {
                existing.deferred.cancel()
            }
            // A different non-expired swarm only supersedes result reuse. Do not cancel the job:
            // another discovery call may already be awaiting it as its required DHT fallback.
            current = null
        }

        lateinit var lease: Lease
        val deferred = scope.async { runner() }
        lease = Lease(
            swarmKeyHex = key,
            startedAtMillis = nowMillis,
            deferred = deferred
        )
        activeLeases += lease
        deferred.invokeOnCompletion {
            synchronized(lock) {
                activeLeases.remove(lease)
            }
        }
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
        activeLeases.toList().forEach { lease -> lease.deferred.cancel() }
        activeLeases.clear()
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
