package com.iptv.tv.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpgTimeCorrectionTest {
    private val base = EpgProgram(
        title = "News",
        description = null,
        category = null,
        startEpochMs = 1_000_000L,
        endEpochMs = 2_000_000L
    )

    @Test
    fun positiveManualOffsetMovesProgrammeForward() {
        val corrected = EpgTimeCorrection.apply(base, 300)
        assertEquals(base.startEpochMs + 18_000_000L, corrected.startEpochMs)
        assertEquals(base.endEpochMs + 18_000_000L, corrected.endEpochMs)
    }

    @Test
    fun sourceWindowMovesOppositeToDisplayCorrection() {
        val (start, end) = EpgTimeCorrection.sourceWindowForDisplayWindow(
            displayStartEpochMs = 20_000_000L,
            displayEndEpochMs = 30_000_000L,
            manualOffsetMinutes = 300
        )
        assertEquals(2_000_000L, start)
        assertEquals(12_000_000L, end)
    }

    @Test
    fun halfHourCorrectionIsSupported() {
        val corrected = EpgTimeCorrection.apply(base, 330)
        assertEquals(base.startEpochMs + 19_800_000L, corrected.startEpochMs)
    }

    @Test
    fun currentAndNextUseCorrectedEpochs() {
        val first = base.copy(title = "First", startEpochMs = 100L, endEpochMs = 200L)
        val second = base.copy(title = "Second", startEpochMs = 200L, endEpochMs = 300L)
        val programs = listOf(first, second)

        assertEquals("First", EpgTimeCorrection.current(programs, 150L)?.title)
        assertEquals("Second", EpgTimeCorrection.next(programs, 150L)?.title)
        assertNull(EpgTimeCorrection.current(programs, 350L))
    }
}
