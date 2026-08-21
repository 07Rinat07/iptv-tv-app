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
    fun stableLifecycleDoesNotSpamObserverBeforePeriodicRefresh() {
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
    fun volatileUsefulAndProducingFlipsAreRateLimited() {
        val messages = mutableListOf<String>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 1, unchoked = 1, producing = 1),
            nowMillis = 1_000L
        )
        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 0, unchoked = 1, producing = 0),
            nowMillis = 1_400L
        )
        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 1, unchoked = 1, producing = 1),
            nowMillis = 1_900L
        )

        assertEquals(1, messages.size)
    }

    @Test
    fun lifecycleStageChangeIsReportedImmediately() {
        val messages = mutableListOf<String>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(snapshot(discovered = 4, connected = 1, handshaked = 0), nowMillis = 1_000L)
        reporter.maybeReport(
            snapshot(
                discovered = 4,
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
        assertTrue(messages.last().contains("handshaked=1"))
        assertTrue(messages.last().contains("producing=1"))
    }

    @Test
    fun volatileStagesAreRefreshedPeriodicallyForRateAndFreshness() {
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
            snapshot(producing = 0, aggregateBytesPerSecond = 0L, freshestMediaAgeMillis = null),
            nowMillis = 2_000L
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
    fun usefulUnchokedPeerWithoutProducerEmitsExplicitProducerGap() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(
            snapshot(
                discovered = 2,
                connected = 1,
                handshaked = 1,
                windowUseful = 1,
                unchoked = 1,
                producing = 0
            ),
            nowMillis = 1_000L
        )

        val gap = events.single { it.first == "embedded_ace_live_producer_gap" }.second
        assertTrue(gap.contains("state=active"))
        assertTrue(gap.contains("handshaked=1"))
        assertTrue(gap.contains("windowUseful=1"))
        assertTrue(gap.contains("unchoked=1"))
        assertTrue(gap.contains("producing=0"))
    }

    @Test
    fun producerGapRequiresUsefulAndUnchokedPeer() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message }
        )

        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 0, unchoked = 1, producing = 0),
            nowMillis = 1_000L
        )
        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 1, unchoked = 0, producing = 0),
            nowMillis = 1_200L
        )

        assertTrue(events.none { it.first == "embedded_ace_live_producer_gap" })
    }

    @Test
    fun persistentProducerGapIsRateLimitedAndThenRefreshed() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )
        val gapSnapshot = snapshot(
            connected = 1,
            handshaked = 1,
            windowUseful = 1,
            unchoked = 1,
            producing = 0
        )

        reporter.maybeReport(gapSnapshot, nowMillis = 1_000L)
        reporter.maybeReport(gapSnapshot, nowMillis = 1_200L)
        reporter.maybeReport(gapSnapshot, nowMillis = 5_999L)
        reporter.maybeReport(gapSnapshot, nowMillis = 6_000L)

        val gapEvents = events.filter { it.first == "embedded_ace_live_producer_gap" }
        assertEquals(2, gapEvents.size)
        assertTrue(gapEvents.all { it.second.contains("state=active") })
    }

    @Test
    fun producerGapEmitsResolutionWhenMediaProducerAppears() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )

        reporter.maybeReport(
            snapshot(connected = 1, handshaked = 1, windowUseful = 1, unchoked = 1, producing = 0),
            nowMillis = 1_000L
        )
        reporter.maybeReport(
            snapshot(
                connected = 1,
                handshaked = 1,
                windowUseful = 1,
                unchoked = 1,
                producing = 1,
                aggregateBytesPerSecond = 200_000L,
                freshestMediaAgeMillis = 0L
            ),
            nowMillis = 1_300L
        )

        val gapEvents = events.filter { it.first == "embedded_ace_live_producer_gap" }
        assertEquals(2, gapEvents.size)
        assertTrue(gapEvents.first().second.contains("state=active"))
        assertTrue(gapEvents.last().second.contains("state=resolved"))
        assertTrue(gapEvents.last().second.contains("producing=1"))
    }

    @Test
    fun runtimeCorrelationIsIncludedInQualityAndProducerGap() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLivePeerDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            context = AceLiveRuntimeDiagnosticsContext(
                startupId = 1_234L,
                runtimeId = 7L,
                generation = 9L,
                path = "direct"
            )
        )

        reporter.maybeReport(
            snapshot(
                connected = 1,
                handshaked = 1,
                windowUseful = 1,
                unchoked = 1,
                producing = 0
            ),
            nowMillis = 1_000L
        )

        val correlated = events.filter { event ->
            event.first == "embedded_ace_live_peer_quality" ||
                event.first == "embedded_ace_live_producer_gap"
        }
        assertEquals(2, correlated.size)
        correlated.forEach { (_, message) ->
            assertTrue(message.contains("startup_id=1234"))
            assertTrue(message.contains("runtime_id=7"))
            assertTrue(message.contains("generation=9"))
            assertTrue(message.contains("path=direct"))
        }
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
