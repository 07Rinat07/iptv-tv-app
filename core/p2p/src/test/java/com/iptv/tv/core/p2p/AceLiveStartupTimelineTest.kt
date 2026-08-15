package com.iptv.tv.core.p2p

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AceLiveStartupTimelineTest {
    @Test
    fun `records first timestamp for each startup milestone`() {
        val timeline = AceLiveStartupTimeline(startedAtMillis = 1_000L)

        val first = timeline.mark(AceLiveStartupMilestone.FIRST_MEDIA, atMillis = 1_450L)
        val repeated = timeline.mark(AceLiveStartupMilestone.FIRST_MEDIA, atMillis = 1_900L)

        assertEquals(450L, first.elapsedMillis)
        assertEquals(first, repeated)
        assertEquals(first, timeline.entry(AceLiveStartupMilestone.FIRST_MEDIA))
    }

    @Test
    fun `snapshot follows canonical startup order rather than callback arrival order`() {
        val timeline = AceLiveStartupTimeline(startedAtMillis = 5_000L)

        timeline.mark(AceLiveStartupMilestone.HTTP_FIRST_READ, atMillis = 5_900L)
        timeline.mark(AceLiveStartupMilestone.TRANSPORT_CONNECTED, atMillis = 5_400L)
        timeline.mark(AceLiveStartupMilestone.TRANSPORT_SELECTION, atMillis = 5_050L)

        assertEquals(
            listOf(
                AceLiveStartupMilestone.TRANSPORT_SELECTION,
                AceLiveStartupMilestone.TRANSPORT_CONNECTED,
                AceLiveStartupMilestone.HTTP_FIRST_READ
            ),
            timeline.snapshot().map { it.milestone }
        )
    }

    @Test
    fun `clock values before startup are clamped and missing milestones stay absent`() {
        val timeline = AceLiveStartupTimeline(startedAtMillis = 2_000L)

        val entry = timeline.mark(AceLiveStartupMilestone.DIRECT_ATTEMPT, atMillis = 1_500L)

        assertEquals(0L, entry.elapsedMillis)
        assertNull(timeline.entry(AceLiveStartupMilestone.MEDIA3_READY))
        assertEquals(
            "phase=direct_attempt, elapsed_ms=0",
            timeline.diagnosticLine(entry)
        )
    }
}
