package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLivePeerDiagnosticsReporterTest {
    @Test
    fun firstSnapshotIsReportedWithAllQualityStagesAndRate() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message }
        )

        reporter.maybeReport(
            snapshot = snapshot(
                discovered = 7,
                connected = 3,
                handshaked = 2,
                windowUseful = 2,
                unchoked = 1,
                producing = 1,
                aggregateBytesPerSecond = 250_000L,
                freshestMediaAgeMillis = 420L
            ),
            nowMillis = 1_000L
        )

        assertEquals(1, events.size)
        assertEquals("embedded_ace_live_peer_quality", events.single().first)
        val message = events.single().second
        assertTrue(message.contains("discovered=7"))
        assertTrue(message.contains("connected=3"))
        assertTrue(message.contains("handshaked=2"))
        assertTrue(message.contains("windowUseful=2"))
        assertTrue(message.contains("unchoked=1"))
        assertTrue(message.contains("producing=1"))
        assertTrue(message.contains("aggregate_bps=250000"))
        assertTrue(message.contains("aggregate_mbps=2.000"))
        assertTrue(message.contains("freshest_media_age_ms=420"))
    }

    @Test
    fun stableStagesDoNotSpamObserverBeforePeriodicRefresh() {
        val messages = mutableListOf<String>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )
        val first = snapshot(producing = 1, aggregateBytesPerSecond = 100_000L, freshestMediaAgeMillis = 10L)
        val drifted = snapshot(producing = 1, aggregateBytesPerSecond = 900_000L, freshestMediaAgeMillis = 2_000L)

        reporter.maybeReport(first, nowMillis = 1_000L)
        reporter.maybeReport(drifted, nowMillis = 1_200L)
        reporter.maybeReport(drifted, nowMillis = 5_999L)

        assertEquals(1, messages.size)
    }

    @Test
    fun materialStageChangeIsReportedImmediately() {
        val messages = mutableListOf<String>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(snapshot(connected = 1, handshaked = 1), nowMillis = 1_000L)
        reporter.maybeReport(
            snapshot(
                connected = 1,
                handshaked = 1,
                windowUseful = 1,
                unchoked = 1,
                producing = 1,
                freshestMediaAgeMillis = 0L
            ),
            nowMillis = 1_200L
        )

        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("producing=1"))
    }

    @Test
    fun stableStagesAreRefreshedPeriodicallyForRateAndFreshness() {
        val messages = mutableListOf<String>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(
            snapshot(producing = 1, aggregateBytesPerSecond = 100_000L, freshestMediaAgeMillis = 100L),
            nowMillis = 1_000L
        )
        reporter.maybeReport(
            snapshot(producing = 1, aggregateBytesPerSecond = 500_000L, freshestMediaAgeMillis = 700L),
            nowMillis = 6_000L
        )

        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("aggregate_bps=500000"))
        assertTrue(messages.last().contains("aggregate_mbps=4.000"))
        assertTrue(messages.last().contains("freshest_media_age_ms=700"))
    }

    @Test
    fun missingFreshMediaUsesExplicitNoneValue() {
        val reporter = AceLivePeerDiagnosticsReporter(observer = { _, _ -> })

        val message = reporter.formatMessage(snapshot())

        assertTrue(message.contains("freshest_media_age_ms=none"))
    }

    private fun snapshot(
        discovered: Int = 0,
        connected: Int = 0,
        handshaked: Int = 0,
        windowUseful: Int = 0,
        unchoked: Int = 0,
        producing: Int = 0,
        aggregateBytesPerSecond: Long = 0L,
        freshestMediaAgeMillis: Long? = null
    ) = AceLivePeerProductionSnapshot(
        discoveredCandidates = discovered,
        connectedPeers = connected,
        handshakedPeers = handshaked,
        windowUsefulPeers = windowUseful,
        unchokedPeers = unchoked,
        producingPeers = producing,
        aggregateBytesPerSecond = aggregateBytesPerSecond,
        freshestMediaAgeMillis = freshestMediaAgeMillis
    )
}
