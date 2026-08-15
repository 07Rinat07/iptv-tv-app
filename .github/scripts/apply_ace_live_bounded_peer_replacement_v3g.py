from pathlib import Path


def replace_once(path: str, old: str, new: str) -> None:
    target = Path(path)
    text = target.read_text()
    count = text.count(old)
    if count != 1:
        raise SystemExit(
            f"{path}: expected exactly one match, found {count}: {old[:160]!r}"
        )
    target.write_text(text.replace(old, new, 1))


Path(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerReplacementPolicy.kt"
).write_text('''package com.iptv.tv.core.p2p

/** Why a non-producing peer has become eligible for bounded replacement. */
internal enum class AceLivePeerReplacementReason(val priority: Int) {
    WINDOW_NOT_USEFUL(4),
    CHOKED(3),
    STALE_MEDIA(2),
    NEVER_PRODUCED(1)
}

internal data class AceLivePeerReplacementDecision(
    val peerId: Long,
    val reason: AceLivePeerReplacementReason,
    val degradedForMillis: Long
)

internal data class AceLivePeerReplacementSettings(
    val targetActivePeers: Int = 6,
    val connectionGraceMillis: Long = 15_000L,
    val degradedEvidenceMillis: Long = 15_000L,
    val noMediaGraceMillis: Long = 30_000L,
    val staleMediaMillis: Long = 15_000L,
    val replacementCooldownMillis: Long = 30_000L
) {
    init {
        require(targetActivePeers > 0) { "targetActivePeers must be positive" }
        require(connectionGraceMillis > 0L) { "connectionGraceMillis must be positive" }
        require(degradedEvidenceMillis > 0L) { "degradedEvidenceMillis must be positive" }
        require(noMediaGraceMillis >= connectionGraceMillis) {
            "noMediaGraceMillis must be >= connectionGraceMillis"
        }
        require(staleMediaMillis > 0L) { "staleMediaMillis must be positive" }
        require(replacementCooldownMillis >= degradedEvidenceMillis) {
            "replacementCooldownMillis must be >= degradedEvidenceMillis"
        }
    }
}

/**
 * Stateful, conservative peer replacement selector.
 *
 * A replacement is possible only while authoritative buffer pressure remains CRITICAL, the active
 * pool is above the baseline target, degradation is sustained, and removing the candidate leaves at
 * least [AceLivePeerReplacementSettings.targetActivePeers] requestable/producing peers. Producing
 * peers are never candidates. One decision starts a cooldown so overlapping refill cycles cannot
 * churn multiple sockets at once.
 */
internal class AceLivePeerReplacementPolicy(
    private val settings: AceLivePeerReplacementSettings = AceLivePeerReplacementSettings()
) {
    private val lock = Any()
    private val evidence = linkedMapOf<Long, DegradationEvidence>()
    private var replacementNotBeforeMillis: Long = 0L

    fun selectCandidate(
        pressure: AceLiveBufferPressure?,
        activePeerIds: Set<Long>,
        peers: List<AceLivePeerQualitySnapshot>,
        nowMillis: Long
    ): AceLivePeerReplacementDecision? = synchronized(lock) {
        require(nowMillis >= 0L) { "nowMillis must be non-negative" }

        val active = peers.filter { snapshot -> snapshot.peerId in activePeerIds }
        val visibleIds = active.mapTo(hashSetOf()) { snapshot -> snapshot.peerId }
        evidence.keys.retainAll(visibleIds)

        if (
            pressure != AceLiveBufferPressure.CRITICAL ||
            activePeerIds.size <= settings.targetActivePeers
        ) {
            evidence.clear()
            return@synchronized null
        }

        val degradedNow = linkedMapOf<Long, AceLivePeerReplacementReason>()
        active.forEach { peer ->
            degradationReason(peer)?.let { reason -> degradedNow[peer.peerId] = reason }
        }
        evidence.keys.retainAll(degradedNow.keys)
        degradedNow.forEach { (peerId, reason) ->
            val previous = evidence[peerId]
            if (previous == null || previous.reason != reason) {
                evidence[peerId] = DegradationEvidence(reason, nowMillis)
            }
        }

        if (nowMillis < replacementNotBeforeMillis) return@synchronized null

        val requestableSurvivor = { candidateId: Long ->
            active.count { peer ->
                peer.peerId != candidateId &&
                    peer.connected &&
                    peer.handshaked &&
                    (peer.producing || (peer.windowUseful && peer.unchoked))
            } >= settings.targetActivePeers
        }

        val decision = evidence.entries
            .mapNotNull { (peerId, state) ->
                val degradedFor = (nowMillis - state.sinceMillis).coerceAtLeast(0L)
                if (
                    degradedFor < settings.degradedEvidenceMillis ||
                    !requestableSurvivor(peerId)
                ) {
                    null
                } else {
                    AceLivePeerReplacementDecision(
                        peerId = peerId,
                        reason = state.reason,
                        degradedForMillis = degradedFor
                    )
                }
            }
            .sortedWith(
                compareByDescending<AceLivePeerReplacementDecision> { it.reason.priority }
                    .thenByDescending { it.degradedForMillis }
                    .thenBy { it.peerId }
            )
            .firstOrNull()

        if (decision != null) {
            evidence.remove(decision.peerId)
            replacementNotBeforeMillis = saturatingAdd(
                nowMillis,
                settings.replacementCooldownMillis
            )
        }
        decision
    }

    private fun degradationReason(
        peer: AceLivePeerQualitySnapshot
    ): AceLivePeerReplacementReason? {
        if (
            !peer.connected ||
            !peer.handshaked ||
            peer.producing ||
            peer.connectedAgeMillis < settings.connectionGraceMillis
        ) {
            return null
        }
        if (!peer.windowUseful) return AceLivePeerReplacementReason.WINDOW_NOT_USEFUL
        if (!peer.unchoked) return AceLivePeerReplacementReason.CHOKED
        if (peer.mediaAgeMillis?.let { it >= settings.staleMediaMillis } == true) {
            return AceLivePeerReplacementReason.STALE_MEDIA
        }
        if (
            peer.totalMediaBytes <= 0L &&
            peer.connectedAgeMillis >= settings.noMediaGraceMillis
        ) {
            return AceLivePeerReplacementReason.NEVER_PRODUCED
        }
        return null
    }

    private fun saturatingAdd(left: Long, right: Long): Long =
        runCatching { Math.addExact(left, right) }.getOrElse { Long.MAX_VALUE }

    private data class DegradationEvidence(
        val reason: AceLivePeerReplacementReason,
        val sinceMillis: Long
    )
}
''')

Path(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLivePeerReplacementPolicyTest.kt"
).write_text('''package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLivePeerReplacementPolicyTest {
    private val settings = AceLivePeerReplacementSettings(
        targetActivePeers = 2,
        connectionGraceMillis = 1_000L,
        degradedEvidenceMillis = 2_000L,
        noMediaGraceMillis = 5_000L,
        staleMediaMillis = 3_000L,
        replacementCooldownMillis = 10_000L
    )

    @Test
    fun sustainedCriticalPressureAndDegradationAreRequired() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            healthy(2),
            degraded(3)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 11_999L))
        val decision = policy.selectCandidate(
            AceLiveBufferPressure.CRITICAL,
            active,
            peers,
            12_000L
        )

        assertEquals(3L, decision?.peerId)
        assertEquals(AceLivePeerReplacementReason.WINDOW_NOT_USEFUL, decision?.reason)
        assertEquals(2_000L, decision?.degradedForMillis)
    }

    @Test
    fun leavingCriticalClearsAccumulatedEvidence() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(healthy(1), healthy(2), degraded(3))
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.TARGET, active, peers, 11_500L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 12_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 13_999L))
        assertEquals(
            3L,
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                active,
                peers,
                14_000L
            )?.peerId
        )
    }

    @Test
    fun producingPeerIsNeverSelected() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            healthy(2),
            degraded(3).copy(producing = true)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 20_000L))
    }

    @Test
    fun replacementCannotReduceRequestablePoolBelowBaseline() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val peers = listOf(
            healthy(1),
            degraded(2),
            degraded(3)
        )
        val active = setOf(1L, 2L, 3L)

        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 10_000L))
        assertNull(policy.selectCandidate(AceLiveBufferPressure.CRITICAL, active, peers, 20_000L))
    }

    @Test
    fun cooldownAllowsAtMostOneReplacementAcrossOverlappingCycles() {
        val policy = AceLivePeerReplacementPolicy(settings)
        val initial = listOf(healthy(1), healthy(2), degraded(3), degraded(4))
        val initialActive = setOf(1L, 2L, 3L, 4L)

        assertNull(
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                initialActive,
                initial,
                10_000L
            )
        )
        val first = policy.selectCandidate(
            AceLiveBufferPressure.CRITICAL,
            initialActive,
            initial,
            12_000L
        )
        assertEquals(3L, first?.peerId)

        val afterFirst = listOf(healthy(1), healthy(2), degraded(4))
        val afterFirstActive = setOf(1L, 2L, 4L)
        assertNull(
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                afterFirstActive,
                afterFirst,
                15_000L
            )
        )
        assertEquals(
            4L,
            policy.selectCandidate(
                AceLiveBufferPressure.CRITICAL,
                afterFirstActive,
                afterFirst,
                22_000L
            )?.peerId
        )
    }

    private fun healthy(peerId: Long) = AceLivePeerQualitySnapshot(
        peerId = peerId,
        connected = true,
        handshaked = true,
        windowUseful = true,
        unchoked = true,
        producing = true,
        recentBytesPerSecond = 1_000_000L,
        mediaAgeMillis = 100L,
        connectedAgeMillis = 20_000L,
        totalMediaBytes = 10_000_000L
    )

    private fun degraded(peerId: Long) = AceLivePeerQualitySnapshot(
        peerId = peerId,
        connected = true,
        handshaked = true,
        windowUseful = false,
        unchoked = true,
        producing = false,
        recentBytesPerSecond = 0L,
        mediaAgeMillis = null,
        connectedAgeMillis = 20_000L,
        totalMediaBytes = 0L
    )
}
''')

# Explicit stop must invalidate quality lifecycle too; cancellation skips the normal disconnected event.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveTcpConnectionPool.kt",
    '''        runtime.writeJob?.cancel()
        runtime.job?.cancelAndJoin()
        poolMutex.withLock {
''',
    '''        runtime.writeJob?.cancel()
        runtime.job?.cancelAndJoin()
        productionTracker.onDisconnected(peerId)
        poolMutex.withLock {
''',
)

# Refill loop owns the serialized replacement/refill cycle boundary.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''data class AceLivePeerRefillCycleResult(
    val discoveryAttempted: Boolean,
    val plannedStarts: Int,
    val startedPeers: Int,
    val immediateStartFailures: Int,
    val poolStale: Boolean
)
''',
    '''data class AceLivePeerRefillCycleResult(
    val discoveryAttempted: Boolean,
    val plannedStarts: Int,
    val startedPeers: Int,
    val immediateStartFailures: Int,
    val poolStale: Boolean,
    val replacedPeerId: Long? = null
)
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val adaptiveProbePeers: suspend () -> Int = { 0 }
) {
''',
    '''    private val startPeer: suspend (peerId: Long, endpoint: AceLiveTcpPeerEndpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val adaptiveProbePeers: suspend () -> Int = { 0 },
    private val replacementPeerId: suspend (activePeerIds: Set<Long>, nowMillis: Long) -> Long? =
        { _, _ -> null },
    private val stopPeer: suspend (peerId: Long) -> Unit = {}
) {
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''        val active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        val recovery = evaluateRecovery()
''',
    '''        var active = activePeerIds()
        coordinator.syncActivePeerIds(active)
        var replacedPeerId: Long? = null
        val selectedForReplacement = replacementPeerId(active, nowMillis)
            ?.takeIf { peerId -> peerId in active }
        if (selectedForReplacement != null) {
            try {
                stopPeer(selectedForReplacement)
                replacedPeerId = selectedForReplacement
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Replacement is an optimization. A failed explicit stop must not fail playback.
            }
            active = activePeerIds()
            if (selectedForReplacement !in active) {
                replacedPeerId = selectedForReplacement
            }
            coordinator.syncActivePeerIds(active)
        }
        val recovery = evaluateRecovery()
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''                immediateStartFailures = 0,
                poolStale = recovery.poolStale
            )
''',
    '''                immediateStartFailures = 0,
                poolStale = recovery.poolStale,
                replacedPeerId = replacedPeerId
            )
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinator.kt",
    '''            immediateStartFailures = failed,
            poolStale = recovery.poolStale
        )
''',
    '''            immediateStartFailures = failed,
            poolStale = recovery.poolStale,
            replacedPeerId = replacedPeerId
        )
''',
)

# Runtime keeps a fresh authoritative pressure timestamp and delegates at most one stop to refill loop.
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''import java.util.concurrent.atomic.AtomicLong
''',
    '''import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''        private val adaptivePeerRefillPolicy = AceLiveAdaptivePeerRefillPolicy()
        private val schedulerRequestDepth = AtomicInteger(BASELINE_IN_FLIGHT_PER_PEER)
        private val adaptivePeerProbePeers = AtomicInteger(0)
''',
    '''        private val adaptivePeerRefillPolicy = AceLiveAdaptivePeerRefillPolicy()
        private val peerReplacementPolicy = AceLivePeerReplacementPolicy(
            AceLivePeerReplacementSettings(targetActivePeers = TARGET_ACTIVE_PEERS)
        )
        private val schedulerRequestDepth = AtomicInteger(BASELINE_IN_FLIGHT_PER_PEER)
        private val adaptivePeerProbePeers = AtomicInteger(0)
        private val authoritativeBufferPressure = AtomicReference<AceLiveBufferPressure?>(null)
        private val authoritativePressureSampleAtMillis = AtomicLong(0L)
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''            },
            adaptiveProbePeers = { adaptivePeerProbePeers.get() }
        )
''',
    '''            },
            adaptiveProbePeers = { adaptivePeerProbePeers.get() },
            replacementPeerId = { activePeerIds, nowMillis ->
                val pressureAt = authoritativePressureSampleAtMillis.get()
                val freshPressure = authoritativeBufferPressure.get().takeIf {
                    pressureAt > 0L &&
                        nowMillis - pressureAt <= BUFFER_PRESSURE_SAMPLE_FRESHNESS_MILLIS
                }
                peerReplacementPolicy.selectCandidate(
                    pressure = freshPressure,
                    activePeerIds = activePeerIds,
                    peers = pool.peerQualitySnapshots(nowMillis),
                    nowMillis = nowMillis
                )?.also { decision ->
                    Log.w(
                        LOG_TAG,
                        "event=peer_replacement_selected peer=${decision.peerId} " +
                            "reason=${decision.reason} degraded_ms=${decision.degradedForMillis}"
                    )
                    runCatching {
                        diagnosticsObserver(
                            "embedded_ace_live_peer_replacement",
                            "phase=selected, peer=${decision.peerId}, reason=${decision.reason}, " +
                                "degraded_ms=${decision.degradedForMillis}"
                        )
                    }
                }?.peerId
            },
            stopPeer = { peerId ->
                pool.stopPeer(peerId)
                Log.w(LOG_TAG, "event=peer_replacement_applied peer=$peerId")
                runCatching {
                    diagnosticsObserver(
                        "embedded_ace_live_peer_replacement",
                        "phase=applied, peer=$peerId"
                    )
                }
            }
        )
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''            val pressure = sample.pressure.pressure
            val requestDepth = adaptiveRequestDepthPolicy.depthFor(pressure)
''',
    '''            val pressure = sample.pressure.pressure
            authoritativeBufferPressure.set(pressure)
            authoritativePressureSampleAtMillis.set(System.currentTimeMillis())
            val requestDepth = adaptiveRequestDepthPolicy.depthFor(pressure)
''',
)
replace_once(
    "core/p2p/src/main/java/com/iptv/tv/core/p2p/AceLiveEmbeddedEngine.kt",
    '''        const val PEER_REFRESH_INTERVAL_MILLIS = 10_000L
        const val PROGRESS_LOG_INTERVAL_MILLIS = 5_000L
''',
    '''        const val PEER_REFRESH_INTERVAL_MILLIS = 10_000L
        const val BUFFER_PRESSURE_SAMPLE_FRESHNESS_MILLIS = 10_000L
        const val PROGRESS_LOG_INTERVAL_MILLIS = 5_000L
''',
)

# Regression coverage for explicit stop lifecycle.
replace_once(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLiveTcpConnectionPoolTest.kt",
    '''        pool.stopPeer(7)
    }
''',
    '''        pool.stopPeer(7)
        val stopped = pool.peerQualitySnapshots(nowMillis = 2L).single { it.peerId == 7L }
        assertFalse(stopped.connected)
        assertFalse(stopped.handshaked)
        assertFalse(stopped.producing)
    }
''',
)

# Refill integration: stop one degraded peer then use the existing CRITICAL probe capacity to refill.
replace_once(
    "core/p2p/src/test/java/com/iptv/tv/core/p2p/AceLivePeerRefillCoordinatorTest.kt",
    '''    @Test
    fun `cancelled start cleanup does not increment peer failure score`() {
''',
    '''    @Test
    fun `bounded replacement stops one peer then refills from existing adaptive demand`() = runBlocking {
        val coordinator = coordinator(target = 6, max = 8, maxStarts = 2)
        val active = linkedSetOf(1L, 2L, 3L, 4L, 5L, 6L, 7L)
        var nextId = 8L
        val replacement = endpoint("192.0.2.88", 8988)
        val loop = AceLivePeerRefillLoop(
            coordinator = coordinator,
            discover = {
                discovery(
                    replacement to setOf(AceLivePeerDiscoverySource.MAINLINE_DHT)
                )
            },
            activePeerIds = { active.toSet() },
            evaluateRecovery = { AceLiveRecoveryPlan(poolStale = false) },
            nextNeededPiece = { 100L },
            allocatePeerId = { nextId++ },
            startPeer = { peerId, _ -> active += peerId },
            adaptiveProbePeers = { 2 },
            replacementPeerId = { _, _ -> 7L },
            stopPeer = { peerId -> active -= peerId }
        )

        val result = loop.runOneCycle(nowMillis = 10_000L)

        assertEquals(7L, result.replacedPeerId)
        assertTrue(result.discoveryAttempted)
        assertEquals(1, result.plannedStarts)
        assertEquals(1, result.startedPeers)
        assertEquals(setOf(1L, 2L, 3L, 4L, 5L, 6L, 8L), active)
    }

    @Test
    fun `cancelled start cleanup does not increment peer failure score`() {
''',
)

# Documentation: V3f complete, V3g current.
replace_once(
    "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md",
    '''Текущий V3f добавляет pressure-aware bounded peer refill без эвристического eviction: `TARGET/HIGH` не расширяют normal pool, `LOW` разрешает один дополнительный probe-peer, `CRITICAL` — два, всегда в пределах существующего `maxActivePeers`. Recovery-stale и pressure probe requests не суммируются — используется больший bounded запрос. Для следующего replacement-инкремента tracker также публикует immutable per-peer quality snapshots с lifecycle/requestability/production/freshness/rate evidence. Принудительное отключение active peers, recovery timing и startup/no-peer/stall bounds этим PR не меняются.
''',
    '''PR #117 завершил V3f pressure-aware bounded peer refill и уже находится в `main`: `TARGET/HIGH` не расширяют normal pool, `LOW` разрешает +1 probe-peer, `CRITICAL` +2, всегда в пределах `maxActivePeers`; recovery и pressure demand используют максимум, а не сумму. Per-peer quality snapshots публикуют lifecycle/requestability/production/freshness/rate evidence. Exact-head Android CI #515, real Torrent TV playback smoke без внешнего Ace Engine, lint, все unit tests и signed ARM TV APK прошли успешно.

Текущий V3g вводит bounded replacement только при устойчивом `CRITICAL`: producing peer никогда не кандидат, degradation должна сохраняться отдельное evidence window, после удаления обязаны оставаться минимум baseline requestable/producing peers, а cooldown разрешает максимум один replacement за цикл/окно. Replacement использует только свежий authoritative pressure sample; исчезнувший loopback consumer не может оставить старый `CRITICAL` как бессрочное основание для eviction. Recovery timing, startup/no-peer/stall bounds и wire protocol этим PR не меняются.
''',
)
replace_once(
    "docs/ACE_LIVE_ADAPTIVE_STREAMING_CORE.md",
    '''V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил первый scheduler feedback: stable authoritative pressure выбирает bounded per-peer request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`. Понижение не requeue/cancel существующие assignments, а лишь блокирует новые до естественного снижения in-flight. V3f расширяет ту же feedback-цепочку только на bounded additive refill: `LOW +1`, `CRITICAL +2`, без eviction. Per-peer quality snapshots готовят доказательную базу для следующего отдельного replacement-инкремента, где отключение peer должно зависеть от подтверждённой деградации, а не от одного low-buffer sample.
''',
    '''V3d завершён как behavior-neutral lifecycle boundary. PR #116/V3e добавил bounded request-depth feedback, PR #117/V3f — additive refill `LOW +1 / CRITICAL +2`. V3g использует накопленные per-peer quality snapshots для консервативного replacement: только sustained `CRITICAL`, только non-producing degraded peer, только при сохранении baseline requestable peers и с cooldown. Сам stop проходит через существующий TCP/session disconnect cleanup, поэтому piece ownership requeue остаётся в прежнем recovery boundary.
''',
)
replace_once(
    "docs/ROADMAP.md",
    '''- PR #116 — authoritative pressure подключён к bounded request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`; Android CI #513, real smoke и signed ARM TV APK прошли успешно.
''',
    '''- PR #116 — authoritative pressure подключён к bounded request depth `HIGH=1 / TARGET=2 / LOW=3 / CRITICAL=4`; Android CI #513, real smoke и signed ARM TV APK прошли успешно;
- PR #117 — pressure-aware additive refill `LOW +1 / CRITICAL +2`, без eviction; per-peer quality snapshots подготовлены для replacement; Android CI #515, real smoke и signed ARM TV APK прошли успешно.
''',
)
replace_once(
    "docs/ROADMAP.md",
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3e (PR #112–#116) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, real loopback lifecycle и bounded adaptive request depth. Текущий **V3f** добавляет pressure-aware additive peer refill: `LOW` разрешает +1 probe-peer, `CRITICAL` +2, без eviction и только в пределах `maxActivePeers`; per-peer quality snapshot готовит следующий bounded replacement шаг. Recovery timing, startup/no-peer/stall bounds и wire protocol пока не меняются.
''',
    '''V1 исправил дефект startup prebuffer: discovery/handshake latency больше не входит в media-throughput estimate. PR #108–#110 последовательно отделили discovery от реальной peer quality, добавили `windowUseful/unchoked` и persistent structured diagnostics. V2d завершён PR #111. V3a–V3f (PR #112–#117) дали stateful buffer pressure, confirmed consumer telemetry, authoritative reader ownership, bounded request depth и pressure-aware additive refill. Текущий **V3g** добавляет bounded replacement: только sustained `CRITICAL`, только подтверждённо degraded non-producing peer, максимум один за cooldown и только при сохранении baseline requestable pool. Recovery timing, startup/no-peer/stall bounds и wire protocol пока не меняются.
''',
)

for helper in (
    Path(".github/scripts/apply_ace_live_bounded_peer_replacement_v3g.py"),
    Path(".github/workflows/apply-ace-live-bounded-peer-replacement-v3g.yml"),
):
    if helper.exists():
        helper.unlink()
