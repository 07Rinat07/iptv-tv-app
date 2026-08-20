package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AceLiveRecoveryDiagnosticsReporterTest {
    @Test
    fun activeStaleSignalIsImmediateThenRateLimitedWithoutFalseResolution() {
        val messages = mutableListOf<String>()
        val reporter = AceLiveRecoveryDiagnosticsReporter(
            observer = { status, message -> messages += "$status $message" },
            periodicIntervalMillis = 5_000L
        )
        val stale = AceLiveRecoveryPlan(
            timedOutRequests = listOf(AceLiveTimedOutRequest(piece = 10, previousPeerId = 2)),
            poolStale = true
        )

        reporter.maybeReport(stale, nowMillis = 1_000L)
        reporter.maybeReport(AceLiveRecoveryPlan(), nowMillis = 1_200L)
        reporter.maybeReport(stale, nowMillis = 5_999L)
        reporter.maybeReport(stale, nowMillis = 6_000L)

        assertEquals(2, messages.size)
        assertTrue(messages.all { it.contains("embedded_ace_live_recovery pool_stale=true") })
        assertTrue(messages.first().contains("timed_out=1"))
    }

    @Test
    fun observerFailureCannotAffectRecovery() {
        val reporter = AceLiveRecoveryDiagnosticsReporter(
            observer = { _, _ -> error("diagnostics unavailable") }
        )

        reporter.maybeReport(AceLiveRecoveryPlan(poolStale = true), nowMillis = 1_000L)
    }

    @Test
    fun staleSignalCarriesRuntimeCorrelation() {
        val messages = mutableListOf<String>()
        val reporter = AceLiveRecoveryDiagnosticsReporter(
            observer = { _, message -> messages += message },
            context = AceLiveRuntimeDiagnosticsContext(
                startupId = 100,
                runtimeId = 4,
                generation = 9,
                path = "metadata"
            )
        )

        reporter.maybeReport(AceLiveRecoveryPlan(poolStale = true), nowMillis = 1_000L)

        val message = messages.single()
        assertTrue(message.contains("startup_id=100"))
        assertTrue(message.contains("runtime_id=4"))
        assertTrue(message.contains("generation=9"))
        assertTrue(message.contains("path=metadata"))
    }
}
