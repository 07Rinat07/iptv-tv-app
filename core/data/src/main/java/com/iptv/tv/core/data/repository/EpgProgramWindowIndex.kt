package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.EpgProgram
import java.util.Locale

internal object EpgProgramWindowIndex {

    fun selectWindow(
        programs: List<EpgProgram>,
        startEpochMs: Long,
        endEpochMs: Long,
        query: String?,
        channelName: String
    ): List<EpgProgram> {
        if (programs.isEmpty() || endEpochMs <= startEpochMs) return emptyList()

        val normalizedQuery = query?.trim()?.lowercase(Locale.ROOT).orEmpty()
        val firstStartedInsideWindow = lowerBoundStart(programs, startEpochMs)
        var index = (firstStartedInsideWindow - 1).coerceAtLeast(0)
        while (index > 0 && programs[index - 1].endEpochMs > startEpochMs) {
            index--
        }

        val result = mutableListOf<EpgProgram>()
        while (index < programs.size) {
            val program = programs[index]
            if (program.startEpochMs >= endEpochMs) break
            if (program.endEpochMs > startEpochMs && matchesQuery(program, channelName, normalizedQuery)) {
                result += program
            }
            index++
        }
        return result
    }

    private fun lowerBoundStart(programs: List<EpgProgram>, targetStartMs: Long): Int {
        var low = 0
        var high = programs.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (programs[mid].startEpochMs < targetStartMs) {
                low = mid + 1
            } else {
                high = mid
            }
        }
        return low
    }

    private fun matchesQuery(program: EpgProgram, channelName: String, normalizedQuery: String): Boolean {
        if (normalizedQuery.isBlank()) return true
        return program.title.lowercase(Locale.ROOT).contains(normalizedQuery) ||
            program.description?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
            program.category?.lowercase(Locale.ROOT)?.contains(normalizedQuery) == true ||
            channelName.lowercase(Locale.ROOT).contains(normalizedQuery)
    }
}
