package com.iptv.tv.feature.epg

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class EpgGuideViewModelHelpersTest {

    @Test
    fun nextEpgVisibleRowLimit_capsAtTotalRows() {
        assertEquals(60, nextEpgVisibleRowLimit(currentVisibleRows = 20, totalRows = 60))
        assertEquals(55, nextEpgVisibleRowLimit(currentVisibleRows = 40, totalRows = 55))
    }

    @Test
    fun buildEpgStatus_formatsFullAndPartialStates() {
        assertEquals("Найдено каналов с EPG: 12", buildEpgStatus(totalRows = 12, visibleRows = 12))
        assertEquals("Показано каналов с EPG: 40 из 95", buildEpgStatus(totalRows = 95, visibleRows = 40))
    }

    @Test
    fun epgWindowForPreset_nowKeepsRollingThreeHourWindow() {
        val now = 1_780_000_000_000L

        val (start, end) = epgWindowForPreset(EpgWindowPreset.NOW, nowMs = now)

        assertEquals(now, start)
        assertEquals(3L * 60L * 60L * 1_000L, end - start)
    }

    @Test
    fun epgWindowForPreset_todayUsesNextLocalMidnightAcrossSpringDst() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = localEpochMs(zone, 2026, Calendar.MARCH, 8, 12)

        val (start, end) = epgWindowForPreset(
            preset = EpgWindowPreset.TODAY,
            nowMs = now,
            timeZone = zone
        )

        assertEquals(23L * 60L * 60L * 1_000L, end - start)
        assertLocalTime(zone, start, 2026, Calendar.MARCH, 8, 0)
        assertLocalTime(zone, end, 2026, Calendar.MARCH, 9, 0)
    }

    @Test
    fun epgWindowForPreset_tomorrowUsesNextLocalMidnightAcrossAutumnDst() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val now = localEpochMs(zone, 2026, Calendar.OCTOBER, 31, 12)

        val (start, end) = epgWindowForPreset(
            preset = EpgWindowPreset.TOMORROW,
            nowMs = now,
            timeZone = zone
        )

        assertEquals(25L * 60L * 60L * 1_000L, end - start)
        assertLocalTime(zone, start, 2026, Calendar.NOVEMBER, 1, 0)
        assertLocalTime(zone, end, 2026, Calendar.NOVEMBER, 2, 0)
    }

    @Test
    fun shouldPrefetchEpgRows_loadsNearEndOnly() {
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 20,
                totalLazyItems = 80,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = false,
                thresholdItems = 8
            )
        )
        assertEquals(
            true,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 73,
                totalLazyItems = 80,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = false,
                thresholdItems = 8
            )
        )
    }

    @Test
    fun shouldPrefetchEpgRows_skipsWhenBusyOrComplete() {
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 99,
                totalLazyItems = 100,
                visibleRows = 100,
                totalRows = 100,
                isLoading = false
            )
        )
        assertEquals(
            false,
            shouldPrefetchEpgRows(
                lastVisibleItemIndex = 99,
                totalLazyItems = 100,
                visibleRows = 40,
                totalRows = 1_000,
                isLoading = true
            )
        )
    }

    private fun localEpochMs(
        zone: TimeZone,
        year: Int,
        month: Int,
        day: Int,
        hour: Int
    ): Long {
        return Calendar.getInstance(zone).apply {
            clear()
            set(year, month, day, hour, 0, 0)
        }.timeInMillis
    }

    private fun assertLocalTime(
        zone: TimeZone,
        epochMs: Long,
        expectedYear: Int,
        expectedMonth: Int,
        expectedDay: Int,
        expectedHour: Int
    ) {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        assertEquals(expectedYear, calendar.get(Calendar.YEAR))
        assertEquals(expectedMonth, calendar.get(Calendar.MONTH))
        assertEquals(expectedDay, calendar.get(Calendar.DAY_OF_MONTH))
        assertEquals(expectedHour, calendar.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, calendar.get(Calendar.MINUTE))
        assertEquals(0, calendar.get(Calendar.SECOND))
        assertEquals(0, calendar.get(Calendar.MILLISECOND))
    }
}
