package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.EpgProgram
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgProgramWindowIndexTest {

    @Test
    fun selectWindow_includesProgramThatStartedBeforeWindowAndOverlaps() {
        val programs = listOf(
            program("Previous", start = 0, end = 100),
            program("Current", start = 100, end = 200),
            program("Next", start = 200, end = 300)
        )

        val result = EpgProgramWindowIndex.selectWindow(
            programs = programs,
            startEpochMs = 50,
            endEpochMs = 150,
            query = null,
            channelName = "News"
        )

        assertEquals(listOf("Previous", "Current"), result.map { it.title })
    }

    @Test
    fun selectWindow_stopsBeforeProgramsOutsideWindow() {
        val programs = listOf(
            program("Before", start = 0, end = 10),
            program("Inside", start = 20, end = 30),
            program("After", start = 100, end = 120)
        )

        val result = EpgProgramWindowIndex.selectWindow(
            programs = programs,
            startEpochMs = 15,
            endEpochMs = 40,
            query = null,
            channelName = "News"
        )

        assertEquals(listOf("Inside"), result.map { it.title })
    }

    @Test
    fun selectWindow_filtersByProgramAndChannelText() {
        val programs = listOf(
            program("Morning Show", start = 0, end = 100, category = "Talk"),
            program("Football Live", start = 100, end = 200, description = "Final match")
        )

        val byProgram = EpgProgramWindowIndex.selectWindow(
            programs = programs,
            startEpochMs = 0,
            endEpochMs = 250,
            query = "football",
            channelName = "Sport HD"
        )
        val byChannel = EpgProgramWindowIndex.selectWindow(
            programs = programs,
            startEpochMs = 0,
            endEpochMs = 250,
            query = "sport",
            channelName = "Sport HD"
        )

        assertEquals(listOf("Football Live"), byProgram.map { it.title })
        assertEquals(2, byChannel.size)
    }

    @Test
    fun selectWindow_returnsEmptyForInvalidWindow() {
        val result = EpgProgramWindowIndex.selectWindow(
            programs = listOf(program("Inside", start = 20, end = 30)),
            startEpochMs = 40,
            endEpochMs = 15,
            query = null,
            channelName = "News"
        )

        assertTrue(result.isEmpty())
    }

    private fun program(
        title: String,
        start: Long,
        end: Long,
        description: String? = null,
        category: String? = null
    ): EpgProgram {
        return EpgProgram(
            title = title,
            description = description,
            category = category,
            startEpochMs = start,
            endEpochMs = end
        )
    }
}
