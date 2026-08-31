package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EpgProgramDisplayPolicy
import java.util.Calendar
import java.util.TimeZone

/**
 * Pure presentation policy for the programme list shown from the Player.
 *
 * Day boundaries are calculated in the device time zone with Calendar so DST transitions remain
 * correct. The repository already bounds each selected-channel schedule; this layer additionally
 * caps visible days/items for a TV-friendly dialog.
 */
internal object StableProgrammeSchedule {
    const val DEFAULT_MAX_ITEMS = 32
    const val DEFAULT_MAX_DAYS = 5

    fun visible(
        programs: List<EpgProgram>,
        nowMs: Long,
        maxItems: Int = DEFAULT_MAX_ITEMS
    ): List<EpgProgram> {
        require(maxItems > 0) { "maxItems must be positive" }
        return programs
            .asSequence()
            .filter(EpgProgramDisplayPolicy::isUsable)
            .filter { it.endEpochMs > nowMs }
            .sortedBy { it.startEpochMs }
            .take(maxItems)
            .toList()
    }

    fun availableDayStarts(
        programs: List<EpgProgram>,
        timeZone: TimeZone = TimeZone.getDefault(),
        maxDays: Int = DEFAULT_MAX_DAYS
    ): List<Long> {
        require(maxDays > 0) { "maxDays must be positive" }
        val days = linkedSetOf<Long>()
        programs
            .asSequence()
            .filter(EpgProgramDisplayPolicy::isUsable)
            .sortedBy { it.startEpochMs }
            .forEach { program ->
                var dayStart = startOfDay(program.startEpochMs, timeZone)
                val finalDayStart = startOfDay(program.endEpochMs - 1L, timeZone)
                while (dayStart <= finalDayStart && days.size < maxDays) {
                    days += dayStart
                    if (dayStart == finalDayStart) break
                    val next = nextDayStart(dayStart, timeZone)
                    if (next <= dayStart) break
                    dayStart = next
                }
            }
        return days.toList()
    }

    fun defaultDayStart(
        programs: List<EpgProgram>,
        nowMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        maxDays: Int = DEFAULT_MAX_DAYS
    ): Long? {
        val days = availableDayStarts(programs, timeZone, maxDays)
        if (days.isEmpty()) return null
        val today = startOfDay(nowMs, timeZone)
        return days.firstOrNull { it == today }
            ?: days.firstOrNull { it > today }
            ?: days.last()
    }

    fun forDay(
        programs: List<EpgProgram>,
        dayStartEpochMs: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        maxItems: Int = DEFAULT_MAX_ITEMS
    ): List<EpgProgram> {
        require(maxItems > 0) { "maxItems must be positive" }
        val normalizedStart = startOfDay(dayStartEpochMs, timeZone)
        val dayEnd = nextDayStart(normalizedStart, timeZone)
        return programs
            .asSequence()
            .filter(EpgProgramDisplayPolicy::isUsable)
            .filter { program ->
                program.startEpochMs < dayEnd && program.endEpochMs > normalizedStart
            }
            .sortedBy { it.startEpochMs }
            .take(maxItems)
            .toList()
    }

    fun startOfDay(
        epochMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    fun nextDayStart(
        dayStartEpochMs: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Long = Calendar.getInstance(timeZone).apply {
        timeInMillis = startOfDay(dayStartEpochMs, timeZone)
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis
}
