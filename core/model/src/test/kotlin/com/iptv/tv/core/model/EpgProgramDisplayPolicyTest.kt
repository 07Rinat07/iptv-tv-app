package com.iptv.tv.core.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgProgramDisplayPolicyTest {
    @Test
    fun `zero length programme is rejected`() {
        assertFalse(EpgProgramDisplayPolicy.isUsable(program("News", 1_000L, 1_000L)))
    }

    @Test
    fun `placeholder programme is rejected`() {
        assertFalse(
            EpgProgramDisplayPolicy.isUsable(
                program("Jadwal belum tersedia", 1_000L, 2_000L)
            )
        )
    }

    @Test
    fun `real programme remains visible`() {
        assertTrue(EpgProgramDisplayPolicy.isUsable(program("Documentary", 1_000L, 2_000L)))
    }

    @Test
    fun `visible programmes are filtered and ordered`() {
        val visible = EpgProgramDisplayPolicy.visiblePrograms(
            listOf(
                program("Later", 3_000L, 4_000L),
                program("Schedule not available", 2_000L, 3_000L),
                program("Now", 1_000L, 2_000L)
            )
        )

        assertEquals(listOf("Now", "Later"), visible.map(EpgProgram::title))
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
