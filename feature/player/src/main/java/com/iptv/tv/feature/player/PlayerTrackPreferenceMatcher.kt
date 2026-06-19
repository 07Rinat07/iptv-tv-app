package com.iptv.tv.feature.player

internal enum class PlayerTrackPreferenceMode {
    AUTO,
    DISABLED,
    SELECTED
}

internal data class PlayerTrackPreference(
    val mode: PlayerTrackPreferenceMode,
    val language: String? = null,
    val label: String? = null
)

internal data class PlayerTrackPreferenceCandidate(
    val groupIndex: Int,
    val trackIndex: Int,
    val language: String?,
    val label: String?,
    val supported: Boolean
)

internal object PlayerTrackPreferenceMatcher {
    fun select(
        preference: PlayerTrackPreference?,
        candidates: List<PlayerTrackPreferenceCandidate>
    ): PlayerTrackPreferenceCandidate? {
        if (preference == null || preference.mode != PlayerTrackPreferenceMode.SELECTED) return null

        val supportedCandidates = candidates.filter { it.supported }
        if (supportedCandidates.isEmpty()) return null

        val preferredLanguage = preference.language.normalizeLanguage()
        val preferredLabel = preference.label.normalizeLabel()
        if (preferredLanguage == null && preferredLabel == null) return null

        return supportedCandidates
            .map { candidate -> candidate to score(preferredLanguage, preferredLabel, candidate) }
            .filter { (_, score) -> score > 0 }
            .maxWithOrNull(
                compareBy<Pair<PlayerTrackPreferenceCandidate, Int>> { it.second }
                    .thenByDescending { -it.first.groupIndex }
                    .thenByDescending { -it.first.trackIndex }
            )
            ?.first
    }

    private fun score(
        preferredLanguage: String?,
        preferredLabel: String?,
        candidate: PlayerTrackPreferenceCandidate
    ): Int {
        val language = candidate.language.normalizeLanguage()
        val label = candidate.label.normalizeLabel()
        var score = 0
        if (preferredLanguage != null && preferredLanguage == language) {
            score += 10
        }
        if (preferredLabel != null && preferredLabel == label) {
            score += 6
        }
        return score
    }

    private fun String?.normalizeLanguage(): String? {
        return this
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() && it != "und" }
    }

    private fun String?.normalizeLabel(): String? {
        return this
            ?.trim()
            ?.lowercase()
            ?.replace(Regex("\\s+"), " ")
            ?.takeIf { it.isNotBlank() }
    }
}
