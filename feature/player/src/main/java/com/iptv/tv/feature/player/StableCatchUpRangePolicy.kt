package com.iptv.tv.feature.player

import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.EpgProgram

/**
 * Presentation-only description of a provider-declared catch-up window.
 *
 * The label is intentionally hidden unless the playlist explicitly declared a positive
 * catch-up-days value and the canonical archive action policy can prove that at least one
 * finished programme is actually playable. This avoids presenting a range for unsupported
 * modes/transports or for metadata that merely looks like catch-up capability.
 */
internal object StableCatchUpRangePolicy {
    fun availableDays(
        channel: Channel?,
        programs: List<EpgProgram>,
        nowMs: Long
    ): Int? {
        val metadata = channel?.catchUp ?: return null
        if (!metadata.daysDeclared) return null
        val days = metadata.days?.takeIf { it > 0 } ?: return null
        val hasConfirmedArchive = programs.any { program ->
            program.endEpochMs <= nowMs &&
                StableCatchUpActionPolicy.state(
                    channel = channel,
                    program = program,
                    nowMs = nowMs
                ) == StableCatchUpActionState.AVAILABLE
        }
        return days.takeIf { hasConfirmedArchive }
    }

    fun label(
        channel: Channel?,
        programs: List<EpgProgram>,
        nowMs: Long
    ): String? = availableDays(channel, programs, nowMs)?.let { days ->
        "Архив: до $days ${archiveDayWord(days)}"
    }

    private fun archiveDayWord(days: Int): String {
        val mod100 = days % 100
        val mod10 = days % 10
        return if (mod10 == 1 && mod100 != 11) "дня" else "дней"
    }
}
