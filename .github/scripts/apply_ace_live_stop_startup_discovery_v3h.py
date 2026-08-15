from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one match, found {count}: {old[:180]!r}"
        )
    target.write_text(text.replace(old, new, 1))


engine = "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt"

replace_once(
    engine,
    '''internal suspend fun runAceLiveSessionWithBackgroundPeerRefill(
    backgroundRefill: suspend () -> Unit,
    driveSession: suspend () -> Unit
) = coroutineScope {
    val refillJob = launch { backgroundRefill() }
    try {
        driveSession()
    } finally {
        refillJob.cancel()
    }
}
''',
    '''internal suspend fun runAceLiveSessionWithBackgroundPeerRefill(
    backgroundRefill: suspend () -> Unit,
    driveSession: suspend () -> Unit
) = coroutineScope {
    val refillJob = launch { backgroundRefill() }
    try {
        driveSession()
    } finally {
        refillJob.cancel()
    }
}

/**
 * Runs startup-only discovery work until it finishes naturally or startup reaches a terminal state.
 *
 * Cancelling startup-specific DHT work must not cancel the enclosing background refill coroutine:
 * after media-ready the normal lightweight refill loop still owns long-running peer maintenance.
 */
internal suspend fun runAceLiveStartupRefillUntilReady(
    startup: CompletableDeferred<Unit>,
    startupRefill: suspend () -> Unit
) = coroutineScope {
    if (startup.isCompleted) return@coroutineScope

    val startupRefillJob = launch {
        if (!startup.isCompleted) startupRefill()
    }
    val startupCompletionJob = launch {
        startup.join()
        startupRefillJob.cancel()
    }
    try {
        startupRefillJob.join()
    } finally {
        startupCompletionJob.cancel()
    }
}
'''
)

replace_once(
    engine,
    '''                        backgroundRefill = {
                            while (
                                startupDhtProbeRefillPending.get() &&
                                !startup.isCompleted
                            ) {
                                // One DHT endpoint frequently accepts no useful live session. A
                                // bounded four-candidate batch gives the TCP pool several chances in
                                // a few seconds, while two independent rounds reduce dependence on a
                                // single routing-table path. Neither walk blocks scheduling or media
                                // ingestion from candidates already in the pool.
                                val probeRefill = refillLoop.runOneCycle()
                                val completedRounds = startupDhtProbeRounds.get()
                                val returnedDhtPeers = lastStartupDhtProbePeerCount.get()
                                startupDhtProbeRefillPending.set(
                                    aceLiveStartupDhtProbeShouldContinue(completedRounds)
                                )
                                Log.i(
                                    LOG_TAG,
                                    "event=startup_dht_probe " +
                                        "round=$completedRounds " +
                                        "returned_peers=$returnedDhtPeers " +
                                        "started_peers=${probeRefill.startedPeers}"
                                )
                                // Discovery can be skipped if enough candidates became active while
                                // this coroutine was scheduled. Do not spin on an unconsumed flag.
                                if (!probeRefill.discoveryAttempted) break
                            }
                            if (
                                startupDhtProbeRounds.get() > 0 &&
                                !startup.isCompleted
                            ) {
                                startupDhtFullExpansionPending.set(true)
                            }
                            if (startupDhtFullExpansionPending.get()) {
                                // Once the first DHT candidate is being probed concurrently by the
                                // session, collect the wider candidate set for resiliency and stale-
                                // peer recovery. This second pass remains off the critical path.
                                val expandedRefill = refillLoop.runOneCycle()
                                Log.i(
                                    LOG_TAG,
                                    "event=startup_dht_expansion " +
                                        "started_peers=${expandedRefill.startedPeers}"
                                )
                            }
                            refillLoop.run()
                        },
''',
    '''                        backgroundRefill = {
                            try {
                                runAceLiveStartupRefillUntilReady(startup) {
                                    while (
                                        startupDhtProbeRefillPending.get() &&
                                        !startup.isCompleted
                                    ) {
                                        // One DHT endpoint frequently accepts no useful live session. A
                                        // bounded four-candidate batch gives the TCP pool several chances in
                                        // a few seconds, while two independent rounds reduce dependence on a
                                        // single routing-table path. Neither walk blocks scheduling or media
                                        // ingestion from candidates already in the pool.
                                        val probeRefill = refillLoop.runOneCycle()
                                        val completedRounds = startupDhtProbeRounds.get()
                                        val returnedDhtPeers = lastStartupDhtProbePeerCount.get()
                                        startupDhtProbeRefillPending.set(
                                            aceLiveStartupDhtProbeShouldContinue(completedRounds)
                                        )
                                        Log.i(
                                            LOG_TAG,
                                            "event=startup_dht_probe " +
                                                "round=$completedRounds " +
                                                "returned_peers=$returnedDhtPeers " +
                                                "started_peers=${probeRefill.startedPeers}"
                                        )
                                        // Discovery can be skipped if enough candidates became active while
                                        // this coroutine was scheduled. Do not spin on an unconsumed flag.
                                        if (!probeRefill.discoveryAttempted) break
                                    }
                                    if (
                                        startupDhtProbeRounds.get() > 0 &&
                                        !startup.isCompleted
                                    ) {
                                        startupDhtFullExpansionPending.set(true)
                                    }
                                    if (
                                        startupDhtFullExpansionPending.get() &&
                                        !startup.isCompleted
                                    ) {
                                        // Once the first DHT candidate is being probed concurrently by the
                                        // session, collect the wider candidate set for resiliency and stale-
                                        // peer recovery. This second pass remains off the critical path.
                                        val expandedRefill = refillLoop.runOneCycle()
                                        Log.i(
                                            LOG_TAG,
                                            "event=startup_dht_expansion " +
                                                "started_peers=${expandedRefill.startedPeers}"
                                        )
                                    }
                                }
                            } finally {
                                startupDhtProbeRefillPending.set(false)
                                startupDhtFullExpansionPending.set(false)
                            }
                            if (currentCoroutineContext().isActive) {
                                refillLoop.run()
                            }
                        },
'''
)

replace_once(
    engine,
    '''            val isInitialDiscovery = initialPeerDiscovery.compareAndSet(true, false)
            val useStartupDhtProbeRefill = !isInitialDiscovery &&
                startupDhtProbeRefillPending.compareAndSet(true, false)
            val useStartupDhtFullExpansion = !isInitialDiscovery &&
                !useStartupDhtProbeRefill &&
                startupDhtFullExpansionPending.compareAndSet(true, false)
''',
    '''            val isInitialDiscovery = initialPeerDiscovery.compareAndSet(true, false)
            val startupDiscoveryActive = !startup.isCompleted
            val useStartupDhtProbeRefill = startupDiscoveryActive &&
                !isInitialDiscovery &&
                startupDhtProbeRefillPending.compareAndSet(true, false)
            val useStartupDhtFullExpansion = startupDiscoveryActive &&
                !isInitialDiscovery &&
                !useStartupDhtProbeRefill &&
                startupDhtFullExpansionPending.compareAndSet(true, false)
'''
)

Path(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngineTest.kt"
).write_text('''package com.iptv.tv.core.p2p

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveEmbeddedEngineTest {
    @Test
    fun mediaStallRequiresCompletedStartupAndExpiredProgressDeadline() {
        assertFalse(
            aceLiveMediaIsStalled(
                startupComplete = false,
                lastMediaAtMillis = 1_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
        assertFalse(
            aceLiveMediaIsStalled(
                startupComplete = true,
                lastMediaAtMillis = 12_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
        assertTrue(
            aceLiveMediaIsStalled(
                startupComplete = true,
                lastMediaAtMillis = 11_000L,
                nowMillis = 31_000L,
                timeoutMillis = 20_000L
            )
        )
    }

    @Test
    fun `session drive does not wait for background dht refill`() = runBlocking {
        val refillStarted = CompletableDeferred<Unit>()
        var driveStartedWhileRefillWasRunning = false

        runAceLiveSessionWithBackgroundPeerRefill(
            backgroundRefill = {
                refillStarted.complete(Unit)
                awaitCancellation()
            },
            driveSession = {
                refillStarted.await()
                driveStartedWhileRefillWasRunning = true
            }
        )

        assertTrue(driveStartedWhileRefillWasRunning)
    }

    @Test
    fun `startup ready cancels startup-only refill without cancelling lightweight continuation`() =
        runBlocking {
            val startup = CompletableDeferred<Unit>()
            val startupRefillStarted = CompletableDeferred<Unit>()
            val startupRefillCancelled = CompletableDeferred<Unit>()
            var lightweightRefillStarted = false

            val runner = launch {
                runAceLiveStartupRefillUntilReady(startup) {
                    startupRefillStarted.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        startupRefillCancelled.complete(Unit)
                    }
                }
                lightweightRefillStarted = true
            }

            startupRefillStarted.await()
            startup.complete(Unit)
            withTimeout(1_000L) {
                startupRefillCancelled.await()
                runner.join()
            }

            assertTrue(lightweightRefillStarted)
            assertTrue(runner.isCompleted)
        }

    @Test
    fun `already ready startup skips startup-only refill`() = runBlocking {
        val startup = CompletableDeferred(Unit)
        var startupRefillCalled = false

        runAceLiveStartupRefillUntilReady(startup) {
            startupRefillCalled = true
        }

        assertFalse(startupRefillCalled)
    }
}
''')

roadmap = "docs/ROADMAP.md"
replace_once(roadmap, "## Текущий срез — 14 августа 2026", "## Текущий срез — 15 августа 2026")
replace_once(
    roadmap,
    '''- PR #117 — pressure-aware additive refill `LOW +1 / CRITICAL +2`, без eviction; per-peer quality snapshots подготовлены для replacement; Android CI #515, real smoke и signed ARM TV APK прошли успешно.
''',
    '''- PR #117 — pressure-aware additive refill `LOW +1 / CRITICAL +2`, без eviction; per-peer quality snapshots подготовлены для replacement; Android CI #515, real smoke и signed ARM TV APK прошли успешно.
- PR #118 — bounded replacement деградировавших peers только при свежем sustained `CRITICAL`, с producing/baseline/cooldown guards; Android CI #517, real smoke и signed ARM TV APK прошли успешно.
'''
)
replace_once(
    roadmap,
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3f (PR #112–#117) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth и pressure-aware additive refill. Текущий **V3g** добавляет bounded replacement: только sustained `CRITICAL`, только подтверждённо degraded non-producing peer, максимум один за cooldown и только при сохранении baseline requestable pool. Recovery timing, startup/no-peer/stall bounds и wire protocol пока не меняются.
''',
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3g (PR #112–#118) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth, pressure-aware additive refill и консервативный replacement деградировавших peers. Текущий **V3h** закрывает startup discovery lifecycle: startup-specific DHT probe/full expansion прекращается после stable-ready, при этом normal lightweight refill остаётся активным. Recovery timing, startup/no-peer/stall bounds и wire protocol не меняются.
'''
)

adaptive = "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md"
replace_once(
    adaptive,
    '''Текущий V3g вводит bounded replacement только при устойчивом `CRITICAL`: producing peer никогда не кандидат, degradation должна сохраняться отдельное evidence window, после удаления обязаны оставаться минимум baseline requestable/producing peers, а cooldown разрешает максимум один replacement за цикл/окно. Replacement использует только свежий authoritative pressure sample; исчезнувший loopback consumer не может оставить старый `CRITICAL` как бессрочное основание для eviction. Recovery timing, startup/no-peer/stall bounds и wire protocol этим PR не меняются.
''',
    '''PR #118 завершил V3g bounded replacement и уже находится в `main`: replacement разрешён только при свежем sustained `CRITICAL`; producing peer никогда не кандидат, degradation должна сохраняться отдельное evidence window, после удаления обязаны оставаться минимум baseline requestable/producing peers, а cooldown разрешает максимум один replacement за цикл/окно. Android CI #517, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

Текущий V3h завершает startup discovery lifecycle. Startup-specific bounded DHT probe/full-expansion должен быть отменён сразу после stable `startup_buffer_ready`, даже если DHT walk уже выполняется; при этом cancellation не должна прекращать обычный long-running lightweight refill. Recovery timing, startup/no-peer/stall bounds, request-depth/refill/replacement policies и wire protocol этим PR не меняются.
'''
)
replace_once(
    adaptive,
    '''V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил bounded request-depth feedback, PR #117/V3f — additive refill `LOW +1 / CRITICAL +2`. V3g использует накопленные per-peer quality snapshots для консервативного replacement: только sustained `CRITICAL`, только non-producing degraded peer, только при сохранении baseline requestable peers и с cooldown. Сам stop проходит через существующий TCP/session disconnect cleanup, поэтому piece ownership requeue остаётся в прежнем recovery boundary.
''',
    '''V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил bounded request-depth feedback, PR #117/V3f — additive refill `LOW +1 / CRITICAL +2`, PR #118/V3g — консервативный replacement только при sustained `CRITICAL` и подтверждённой degradation. Сам stop проходит через существующий TCP/session disconnect cleanup, поэтому piece ownership requeue остаётся в прежнем recovery boundary. V3h отдельно завершает startup discovery lifecycle: после stable-ready startup-only DHT expansion прекращается, но normal lightweight refill продолжает поддерживать peer pool.
'''
)
replace_once(
    adaptive,
    '''- [ ] wire loopback `Opened / Delivered / Closed` into authoritative pressure path (PR #115, under acceptance);
- [ ] exact-head CI + real Torrent TV smoke for V3d;
- [ ] adaptive request depth/in-flight;
- [ ] peer replacement based on producing quality;
- [ ] startup discovery shutdown after stable-ready;
- [ ] bounded recovery regression matrix.
''',
    '''- [x] wire loopback `Opened / Delivered / Closed` into authoritative pressure path (PR #115);
- [x] exact-head CI + real Torrent TV smoke for V3d (Android CI #511 / PR #115);
- [x] adaptive request depth/in-flight (PR #116);
- [x] pressure-aware bounded peer refill (PR #117);
- [x] peer replacement based on producing quality (PR #118);
- [ ] startup discovery shutdown after stable-ready (V3h);
- [ ] bounded recovery regression matrix.
'''
)

Path(".github/scripts/apply_ace_live_stop_startup_discovery_v3h.py").unlink()
Path(".github/workflows/apply-ace-live-stop-startup-discovery-v3h.yml").unlink()
