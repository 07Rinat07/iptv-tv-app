package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Test

class StableProgrammeScheduleTest {
    @Test
    fun `visible schedule filters invalid and past rows then sorts`() {
        val now = 10_000L
        val visible = StableProgrammeSchedule.visible(
            programs = listOf(
                program("Later", 20_000L, 30_000L),
                program("Past", 1_000L, 9_000L),
                program("Schedule not available", 11_000L, 12_000L),
                program("Current", 9_000L, 15_000L),
                program("Broken", 16_000L, 16_000L),
                program("Next", 15_000L, 20_000L)
            ),
            nowMs = now
        )

        assertEquals(listOf("Current", "Next", "Later"), visible.map { it.title })
    }

    @Test
    fun `visible schedule keeps dialog bounded`() {
        val programs = (0 until 40).map { index ->
            program(
                title = "Show $index",
                start = 10_000L + index * 1_000L,
                end = 11_000L + index * 1_000L
            )
        }

        val visible = StableProgrammeSchedule.visible(
            programs = programs,
            nowMs = 10_000L,
            maxItems = 8
        )

        assertEquals(8, visible.size)
        assertEquals("Show 0", visible.first().title)
        assertEquals("Show 7", visible.last().title)
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )
}
