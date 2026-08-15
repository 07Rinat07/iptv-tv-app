package com.iptv.tv.core.p2p

/**
 * Bounded per-peer piece request depth selected from authoritative consumer buffer pressure.
 *
 * The pressure classifier already owns hysteresis, so this policy is deliberately stateless. A
 * missing consumer sample keeps the historical baseline instead of speculatively increasing work.
 */
internal data class AceLiveAdaptiveRequestDepthSettings(
    val criticalDepth: Int = 4,
    val lowDepth: Int = 3,
    val targetDepth: Int = 2,
    val highDepth: Int = 1,
    val hardMaxDepth: Int = 4
) {
    init {
        require(highDepth > 0) { "highDepth must be positive" }
        require(highDepth <= targetDepth) { "highDepth must not exceed targetDepth" }
        require(targetDepth <= lowDepth) { "targetDepth must not exceed lowDepth" }
        require(lowDepth <= criticalDepth) { "lowDepth must not exceed criticalDepth" }
        require(criticalDepth <= hardMaxDepth) { "criticalDepth must not exceed hardMaxDepth" }
    }
}

internal class AceLiveAdaptiveRequestDepthPolicy(
    private val settings: AceLiveAdaptiveRequestDepthSettings =
        AceLiveAdaptiveRequestDepthSettings()
) {
    fun depthFor(pressure: AceLiveBufferPressure?): Int {
        val requested = when (pressure) {
            AceLiveBufferPressure.CRITICAL -> settings.criticalDepth
            AceLiveBufferPressure.LOW -> settings.lowDepth
            AceLiveBufferPressure.TARGET -> settings.targetDepth
            AceLiveBufferPressure.HIGH -> settings.highDepth
            null -> settings.targetDepth
        }
        return requested.coerceIn(1, settings.hardMaxDepth)
    }
}
