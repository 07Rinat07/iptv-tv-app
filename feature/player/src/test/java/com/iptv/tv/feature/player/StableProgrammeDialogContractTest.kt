package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
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

    @Test
    fun `day labels use today tomorrow and compact date`() {
        val zone = TimeZone.getTimeZone("UTC")
        val now = instant(zone, 2026, Calendar.AUGUST, 31, 12)
        val today = StableProgrammeSchedule.startOfDay(now, zone)
        val tomorrow = StableProgrammeSchedule.nextDayStart(today, zone)
        val later = StableProgrammeSchedule.nextDayStart(tomorrow, zone)

        assertEquals("Сегодня", stableProgrammeDayLabel(today, now, zone, Locale.US))
        assertEquals("Завтра", stableProgrammeDayLabel(tomorrow, now, zone, Locale.US))
        assertEquals("02.09", stableProgrammeDayLabel(later, now, zone, Locale.US))
    }

    private fun program(title: String, start: Long, end: Long): EpgProgram = EpgProgram(
        title = title,
        description = null,
        category = null,
        startEpochMs = start,
        endEpochMs = end
    )

    private fun instant(zone: TimeZone, year: Int, month: Int, day: Int, hour: Int): Long =
        Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
}
