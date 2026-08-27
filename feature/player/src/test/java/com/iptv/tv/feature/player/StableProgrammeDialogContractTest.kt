package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StableProgrammeDialogContractTest {
    @Test
    fun `current programme remains first when it overlaps now`() {
        val schedule = StableProgrammeSchedule.visible(
            programs = listOf(
                program("Next", 20_000L, 30_000L),
                program("Current", 5_000L, 20_000L)
            ),
            nowMs = 10_000L
        )

        assertEquals("Current", schedule.first().title)
    }

    @Test
    fun `programme panel leaves live video visible`() {
        assertTrue(STABLE_PROGRAMME_PANEL_WIDTH_FRACTION < 0.5f)
        assertTrue(STABLE_PROGRAMME_PANEL_WIDTH_FRACTION >= 0.4f)
        assertEquals(460f, stableProgrammePanelWidthDp(1_000f), 0.01f)
    }

    @Test
    fun `programme panel width remains capped on large TV surfaces`() {
        assertEquals(
            STABLE_PROGRAMME_PANEL_MAX_WIDTH_DP,
            stableProgrammePanelWidthDp(1_920f),
            0.01f
        )
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
