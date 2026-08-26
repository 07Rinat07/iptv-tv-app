package com.iptv.tv.core.model

object EpgTimeCorrection {
    fun sourceWindowForDisplayWindow(
        displayStartEpochMs: Long,
        displayEndEpochMs: Long,
        manualOffsetMinutes: Int
    ): Pair<Long, Long> {
        require(displayEndEpochMs >= displayStartEpochMs) { "EPG window end must not precede start" }
        val delta = EpgSettingsPolicy.offsetMillis(manualOffsetMinutes)
        return safeAdd(displayStartEpochMs, -delta) to safeAdd(displayEndEpochMs, -delta)
    }

    fun apply(program: EpgProgram, manualOffsetMinutes: Int): EpgProgram {
        val delta = EpgSettingsPolicy.offsetMillis(manualOffsetMinutes)
        if (delta == 0L) return program
        return program.copy(
            startEpochMs = safeAdd(program.startEpochMs, delta),
            endEpochMs = safeAdd(program.endEpochMs, delta)
        )
    }

    fun apply(programs: Iterable<EpgProgram>, manualOffsetMinutes: Int): List<EpgProgram> {
        return programs
            .asSequence()
            .map { program -> apply(program, manualOffsetMinutes) }
            .sortedBy(EpgProgram::startEpochMs)
            .toList()
    }

    fun current(programs: Iterable<EpgProgram>, nowMs: Long): EpgProgram? =
        programs.firstOrNull { program ->
            program.startEpochMs <= nowMs && nowMs < program.endEpochMs
        }

    fun next(programs: Iterable<EpgProgram>, nowMs: Long): EpgProgram? =
        programs
            .asSequence()
            .filter { program -> program.startEpochMs > nowMs }
            .minByOrNull(EpgProgram::startEpochMs)

    private fun safeAdd(value: Long, delta: Long): Long {
        if (delta > 0L && value > Long.MAX_VALUE - delta) return Long.MAX_VALUE
        if (delta < 0L && value < Long.MIN_VALUE - delta) return Long.MIN_VALUE
        return value + delta
    }
}
