package com.iptv.tv.core.p2p

/**
 * Maps authoritative consumer pressure to bounded extra peer probes.
 *
 * Refill is intentionally additive only: this policy never selects or evicts an active peer.
 * Replacement needs per-peer quality evidence and remains a separate increment.
 */
internal data class AceLiveAdaptivePeerRefillSettings(
    val lowExtraPeers: Int = 1,
    val criticalExtraPeers: Int = 2,
    val hardMaxExtraPeers: Int = 2
) {
    init {
        require(lowExtraPeers >= 0) { "lowExtraPeers must be non-negative" }
        require(criticalExtraPeers >= lowExtraPeers) {
            "criticalExtraPeers must be >= lowExtraPeers"
        }
        require(criticalExtraPeers <= hardMaxExtraPeers) {
            "criticalExtraPeers must not exceed hardMaxExtraPeers"
        }
    }
}

internal class AceLiveAdaptivePeerRefillPolicy(
    private val settings: AceLiveAdaptivePeerRefillSettings =
        AceLiveAdaptivePeerRefillSettings()
) {
    fun extraProbePeersFor(pressure: AceLiveBufferPressure?): Int {
        val requested = when (pressure) {
            AceLiveBufferPressure.CRITICAL -> settings.criticalExtraPeers
            AceLiveBufferPressure.LOW -> settings.lowExtraPeers
            AceLiveBufferPressure.TARGET,
            AceLiveBufferPressure.HIGH,
            null -> 0
        }
        return requested.coerceIn(0, settings.hardMaxExtraPeers)
    }
}
