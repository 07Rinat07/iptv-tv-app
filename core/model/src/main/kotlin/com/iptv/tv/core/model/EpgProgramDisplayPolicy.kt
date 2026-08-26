package com.iptv.tv.core.model

import java.util.Locale

/**
 * Shared fail-closed policy for programme entries that are safe to expose in TV UI.
 *
 * XMLTV feeds in the field sometimes publish zero-length rows or human-readable placeholders
 * instead of real programme metadata. Those rows must not become "now" programmes, recordings,
 * progress bars or channel-list summaries.
 */
object EpgProgramDisplayPolicy {
    fun isUsable(program: EpgProgram): Boolean {
        return program.endEpochMs > program.startEpochMs && !isPlaceholderTitle(program.title)
    }

    fun visiblePrograms(programs: Iterable<EpgProgram>): List<EpgProgram> {
        return programs
            .asSequence()
            .filter(::isUsable)
            .sortedBy(EpgProgram::startEpochMs)
            .toList()
    }

    fun isPlaceholderTitle(title: String): Boolean {
        val normalized = title
            .trim()
            .lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) return true
        return normalized in PLACEHOLDER_TITLES
    }

    private val PLACEHOLDER_TITLES = setOf(
        "jadwal belum tersedia",
        "schedule not available",
        "programme not available",
        "program not available",
        "programme unavailable",
        "program unavailable",
        "no programme information",
        "no program information",
        "программа недоступна",
        "программа не найдена",
        "нет информации о программе"
    )
}
