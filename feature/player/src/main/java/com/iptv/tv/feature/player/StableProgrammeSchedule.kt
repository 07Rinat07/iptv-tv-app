package com.iptv.tv.feature.player

import com.iptv.tv.core.model.EpgProgram
import com.iptv.tv.core.model.EpgProgramDisplayPolicy

/**
 * Pure presentation policy for the programme list shown from the Player.
 *
 * The Player already owns the selected channel EPG state. This policy deliberately performs no
 * repository or network work: it only filters unusable/past rows, sorts by start time and keeps the
 * TV dialog bounded.
 */
internal object StableProgrammeSchedule {
    const val DEFAULT_MAX_ITEMS = 32

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
}
