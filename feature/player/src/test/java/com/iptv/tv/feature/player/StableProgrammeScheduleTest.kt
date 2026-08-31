package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import java.util.Calendar
import java.util.TimeZone
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

    @Test
    fun `available days are local distinct sorted and default to today`() {
        val zone = TimeZone.getTimeZone("UTC")
        val yesterday = instant(zone, 2026, Calendar.AUGUST, 30, 22)
        val todayMorning = instant(zone, 2026, Calendar.AUGUST, 31, 8)
        val todayEvening = instant(zone, 2026, Calendar.AUGUST, 31, 20)
        val tomorrow = instant(zone, 2026, Calendar.SEPTEMBER, 1, 9)
        val now = instant(zone, 2026, Calendar.AUGUST, 31, 12)
        val programs = listOf(
            program("Yesterday", yesterday, yesterday + HOUR_MS),
            program("Morning", todayMorning, todayMorning + HOUR_MS),
            program("Evening", todayEvening, todayEvening + HOUR_MS),
            program("Tomorrow", tomorrow, tomorrow + HOUR_MS)
        )

        val days = StableProgrammeSchedule.availableDayStarts(programs, zone)

        assertEquals(
            listOf(
                dayStart(zone, 2026, Calendar.AUGUST, 30),
                dayStart(zone, 2026, Calendar.AUGUST, 31),
                dayStart(zone, 2026, Calendar.SEPTEMBER, 1)
            ),
            days
        )
        assertEquals(
            dayStart(zone, 2026, Calendar.AUGUST, 31),
            StableProgrammeSchedule.defaultDayStart(programs, now, zone)
        )
    }

    @Test
    fun `cross midnight programme exposes both local days`() {
        val zone = TimeZone.getTimeZone("UTC")
        val crossingStart = instant(zone, 2026, Calendar.AUGUST, 31, 23)
        val days = StableProgrammeSchedule.availableDayStarts(
            programs = listOf(program("Crossing", crossingStart, crossingStart + 2 * HOUR_MS)),
            timeZone = zone
        )

        assertEquals(
            listOf(
                dayStart(zone, 2026, Calendar.AUGUST, 31),
                dayStart(zone, 2026, Calendar.SEPTEMBER, 1)
            ),
            days
        )
    }

    @Test
    fun `day schedule keeps midnight overlap and filters invalid rows`() {
        val zone = TimeZone.getTimeZone("UTC")
        val selectedDay = dayStart(zone, 2026, Calendar.SEPTEMBER, 1)
        val crossingStart = instant(zone, 2026, Calendar.AUGUST, 31, 23)
        val insideStart = instant(zone, 2026, Calendar.SEPTEMBER, 1, 2)
        val nextDayStart = instant(zone, 2026, Calendar.SEPTEMBER, 2, 1)

        val schedule = StableProgrammeSchedule.forDay(
            programs = listOf(
                program("Next day", nextDayStart, nextDayStart + HOUR_MS),
                program("Inside", insideStart, insideStart + HOUR_MS),
                program("Crossing", crossingStart, crossingStart + 2 * HOUR_MS),
                program("Schedule not available", insideStart + HOUR_MS, insideStart + 2 * HOUR_MS),
                program("Broken", insideStart, insideStart)
            ),
            dayStartEpochMs = selectedDay,
            timeZone = zone
        )

        assertEquals(listOf("Crossing", "Inside"), schedule.map { it.title })
    }

    @Test
    fun `next local day start follows DST instead of fixed 24 hours`() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val springForwardDay = dayStart(zone, 2026, Calendar.MARCH, 8)
        val nextDay = StableProgrammeSchedule.nextDayStart(springForwardDay, zone)

        assertEquals(23 * HOUR_MS, nextDay - springForwardDay)
        assertEquals(dayStart(zone, 2026, Calendar.MARCH, 9), nextDay)
    }

    @Test
    fun `available day list and selected day rows stay bounded`() {
        val zone = TimeZone.getTimeZone("UTC")
        val programs = (0 until 8).flatMap { dayIndex ->
            (0 until 6).map { itemIndex ->
                val start = dayStart(zone, 2026, Calendar.SEPTEMBER, 1 + dayIndex) + itemIndex * HOUR_MS
                program("D$dayIndex-$itemIndex", start, start + HOUR_MS)
            }
        }

        val days = StableProgrammeSchedule.availableDayStarts(programs, zone, maxDays = 3)
        val selected = StableProgrammeSchedule.forDay(
            programs = programs,
            dayStartEpochMs = days.first(),
            timeZone = zone,
            maxItems = 4
        )

        assertEquals(3, days.size)
        assertEquals(listOf("D0-0", "D0-1", "D0-2", "D0-3"), selected.map { it.title })
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

    private fun dayStart(zone: TimeZone, year: Int, month: Int, day: Int): Long =
        instant(zone, year, month, day, 0)

    private companion object {
        const val HOUR_MS = 60L * 60L * 1_000L
    }
}
