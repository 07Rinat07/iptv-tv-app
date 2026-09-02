package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Test

class AceLiveStartupProducingTimelineTest {
    @Test
    fun `first producing peer becomes a stable startup milestone after first media`() {
        val records = mutableListOf<String>()
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 1_000L,
            diagnosticsObserver = { status, message ->
                if (status == AceLiveStartupTimelineDiagnostics.STATUS) records += message
            }
        )

        diagnostics.onPeerProduction(
            snapshot = productionSnapshot(windowUsefulPeers = 1, producingPeers = 0),
            atMillis = 1_090L
        )
        diagnostics.onFirstMedia(atMillis = 1_100L)
        diagnostics.onPeerProduction(
            snapshot = productionSnapshot(windowUsefulPeers = 1, producingPeers = 1),
            atMillis = 1_110L
        )
        diagnostics.onPeerProduction(
            snapshot = productionSnapshot(windowUsefulPeers = 1, producingPeers = 1),
            atMillis = 1_900L
        )

        assertEquals(
            listOf(
                AceLiveStartupMilestone.USEFUL_WINDOW,
                AceLiveStartupMilestone.FIRST_MEDIA,
                AceLiveStartupMilestone.PRODUCING
            ),
            diagnostics.snapshot().map { it.milestone }
        )
        assertEquals(
            listOf("phase=producing, elapsed_ms=110"),
            records.filter { it.startsWith("phase=producing,") }
        )
    }

    @Test
    fun `useful window without producing peer does not infer producing milestone`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 2_000L,
            diagnosticsObserver = { _, _ -> }
        )

        diagnostics.onPeerProduction(
            snapshot = productionSnapshot(windowUsefulPeers = 1, producingPeers = 0),
            atMillis = 2_100L
        )

        assertEquals(
            listOf(AceLiveStartupMilestone.USEFUL_WINDOW),
            diagnostics.snapshot().map { it.milestone }
        )
    }

    private fun productionSnapshot(
        windowUsefulPeers: Int,
        producingPeers: Int
    ) = AceLivePeerProductionSnapshot(
        discoveredCandidates = 3,
        connectedPeers = 2,
        handshakedPeers = 2,
        windowUsefulPeers = windowUsefulPeers,
        unchokedPeers = 1,
        producingPeers = producingPeers,
        aggregateBytesPerSecond = if (producingPeers > 0) 512_000L else 0L,
        freshestMediaAgeMillis = if (producingPeers > 0) 0L else null
    )
}
