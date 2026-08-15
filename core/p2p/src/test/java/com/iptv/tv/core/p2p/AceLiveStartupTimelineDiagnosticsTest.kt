package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveStartupTimelineDiagnosticsTest {
    @Test
    fun `emits one stable diagnostic record for first milestone occurrence`() {
        val records = mutableListOf<Pair<String, String>>()
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 1_000L,
            clockMillis = { 1_250L },
            diagnosticsObserver = { status, message -> records += status to message }
        )

        val first = diagnostics.mark(AceLiveStartupMilestone.TRANSPORT_CONNECTED)
        val duplicate = diagnostics.mark(
            AceLiveStartupMilestone.TRANSPORT_CONNECTED,
            atMillis = 1_900L
        )

        assertEquals(250L, first?.elapsedMillis)
        assertNull(duplicate)
        assertEquals(
            listOf(
                AceLiveStartupTimelineDiagnostics.STATUS to
                    "phase=connected, elapsed_ms=250"
            ),
            records
        )
    }

    @Test
    fun `observer failure cannot change timeline evidence`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 2_000L,
            clockMillis = { 2_100L },
            diagnosticsObserver = { _, _ -> error("diagnostics sink unavailable") }
        )

        diagnostics.mark(AceLiveStartupMilestone.HTTP_READER_OPEN)

        assertEquals(
            listOf(
                AceLiveStartupTimelineEntry(
                    milestone = AceLiveStartupMilestone.HTTP_READER_OPEN,
                    elapsedMillis = 100L
                )
            ),
            diagnostics.snapshot()
        )
    }

    @Test
    fun `snapshot remains in canonical milestone order when runtime reports out of order`() {
        val diagnostics = AceLiveStartupTimelineDiagnostics(
            startedAtMillis = 5_000L,
            diagnosticsObserver = { _, _ -> }
        )

        diagnostics.mark(AceLiveStartupMilestone.HTTP_FIRST_READ, atMillis = 5_400L)
        diagnostics.mark(AceLiveStartupMilestone.FIRST_CANDIDATE, atMillis = 5_100L)
        diagnostics.mark(AceLiveStartupMilestone.BUFFER_READY, atMillis = 5_300L)

        assertEquals(
            listOf(
                AceLiveStartupMilestone.FIRST_CANDIDATE,
                AceLiveStartupMilestone.BUFFER_READY,
                AceLiveStartupMilestone.HTTP_FIRST_READ
            ),
            diagnostics.snapshot().map { it.milestone }
        )
    }
}
