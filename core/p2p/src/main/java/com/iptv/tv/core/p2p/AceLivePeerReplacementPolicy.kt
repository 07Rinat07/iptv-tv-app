package com.iptv.tv.core.p2p

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
