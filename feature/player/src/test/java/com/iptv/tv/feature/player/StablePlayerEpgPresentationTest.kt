package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StablePlayerEpgPresentationTest {
    @Test
    fun `current programme ignores placeholder and zero duration entries`() {
        val now = 10_000L
        val programs = listOf(
            program("Jadwal belum tersedia", 5_000L, 15_000L),
            program("Broken", 10_000L, 10_000L),
            program("Real show", 9_000L, 11_000L)
        )

        assertEquals("Real show", stableCurrentProgram(programs, now)?.title)
    }

    @Test
    fun `next programme ignores placeholder entries`() {
        val now = 10_000L
        val programs = listOf(
            program("Schedule not available", 11_000L, 12_000L),
            program("Documentary", 12_000L, 13_000L)
        )

        assertEquals("Documentary", stableNextProgram(programs, current = null, nowMs = now)?.title)
    }

    @Test
    fun `only invalid entries produce no current programme`() {
        assertNull(
            stableCurrentProgram(
                listOf(program("Программа не найдена", 5_000L, 15_000L)),
                nowMs = 10_000L
            )
        )
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram {
        return EpgProgram(
            title = title,
            description = null,
            category = null,
            startEpochMs = start,
            endEpochMs = end
        )
    }
}
