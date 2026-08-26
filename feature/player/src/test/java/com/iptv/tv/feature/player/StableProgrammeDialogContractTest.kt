package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
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

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
