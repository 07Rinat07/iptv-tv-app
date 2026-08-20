package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveProducerBoundaryDiagnosticsReporterTest {
    @Test
    fun firstObservationOfEachStageIsEmittedImmediately() {
        val events = mutableListOf<Pair<String, String>>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { status, message -> events += status to message },
            periodicIntervalMillis = 5_000L
        )

        reporter.record(
            sessionId = 7,
            stage = AceLiveProducerBoundaryStage.SCHEDULED,
            peerId = 2,
            piece = 100,
            nowMillis = 1_000L
        )
        reporter.record(
            sessionId = 7,
            stage = AceLiveProducerBoundaryStage.SELECTED,
            peerId = 2,
            piece = 100,
            nowMillis = 1_100L
        )
        reporter.record(
            sessionId = 7,
            stage = AceLiveProducerBoundaryStage.CHUNK_INGRESS,
            peerId = 2,
            piece = 100,
            nowMillis = 1_200L
        )

        assertEquals(3, events.size)
        assertTrue(events.all { it.first == "embedded_ace_live_producer_boundary" })
        assertTrue(events[0].second.contains("stage=scheduled"))
        assertTrue(events[1].second.contains("stage=selected"))
        assertTrue(events[2].second.contains("stage=chunk_ingress"))
        assertTrue(events.last().second.contains("scheduled=1"))
        assertTrue(events.last().second.contains("selected=1"))
        assertTrue(events.last().second.contains("chunk_ingress=1"))
    }

    @Test
    fun repeatedStageIsRateLimitedAndAggregated() {
        val messages = mutableListOf<String>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { _, message -> messages += message },
            periodicIntervalMillis = 5_000L
        )

        reporter.record(1, AceLiveProducerBoundaryStage.SCHEDULED, 3, 10, nowMillis = 1_000L)
        reporter.record(1, AceLiveProducerBoundaryStage.SCHEDULED, 3, 11, nowMillis = 1_100L)
        reporter.record(1, AceLiveProducerBoundaryStage.SCHEDULED, 3, 12, nowMillis = 5_999L)
        reporter.record(1, AceLiveProducerBoundaryStage.SCHEDULED, 3, 13, nowMillis = 6_000L)

        assertEquals(2, messages.size)
        assertTrue(messages.last().contains("scheduled=4"))
        assertTrue(messages.last().contains("piece=13"))
    }

    @Test
    fun rejectedChunkCarriesBoundedDispositionAndCounters() {
        val messages = mutableListOf<String>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { _, message -> messages += message }
        )

        reporter.record(
            sessionId = 9,
            stage = AceLiveProducerBoundaryStage.CHUNK_REJECTED,
            peerId = 4,
            piece = 55,
            disposition = "WRONG_OWNER",
            nowMillis = 2_000L
        )

        assertEquals(1, messages.size)
        val message = messages.single()
        assertTrue(message.contains("session=9"))
        assertTrue(message.contains("stage=chunk_rejected"))
        assertTrue(message.contains("peer=4"))
        assertTrue(message.contains("piece=55"))
        assertTrue(message.contains("disposition=WRONG_OWNER"))
        assertTrue(message.contains("chunk_rejected=1"))
    }

    @Test
    fun persistentRuntimeContextAndOutputBytesAreIncluded() {
        val messages = mutableListOf<String>()
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { _, message -> messages += message },
            context = AceLiveRuntimeDiagnosticsContext(
                startupId = 100,
                runtimeId = 4,
                generation = 9,
                path = "direct_retry"
            )
        )

        reporter.record(
            sessionId = 2,
            stage = AceLiveProducerBoundaryStage.MEDIA_APPENDED,
            peerId = 7,
            piece = 55,
            bytes = 18_800,
            nowMillis = 2_000L
        )

        val message = messages.single()
        assertTrue(message.contains("stage=media_appended"))
        assertTrue(message.contains("bytes=18800"))
        assertTrue(message.contains("startup_id=100"))
        assertTrue(message.contains("runtime_id=4"))
        assertTrue(message.contains("generation=9"))
        assertTrue(message.contains("path=direct_retry"))
    }

    @Test
    fun observerFailureCannotChangeRuntimeBehavior() {
        val reporter = AceLiveProducerBoundaryDiagnosticsReporter(
            observer = { _, _ -> error("diagnostics sink unavailable") }
        )

        reporter.record(
            sessionId = 1,
            stage = AceLiveProducerBoundaryStage.PIECE_COMPLETED,
            peerId = 1,
            piece = 1,
            nowMillis = 1_000L
        )
    }
}
