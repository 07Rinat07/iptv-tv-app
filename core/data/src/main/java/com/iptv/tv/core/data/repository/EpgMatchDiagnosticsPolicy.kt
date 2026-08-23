package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.PlaylistEpgDiagnostics

/** One conservative matcher result for a physical playlist channel. */
internal data class EpgMatchObservation(
    val matchedBy: String,
    val hasPrograms: Boolean
)

/**
 * Builds deterministic diagnostics without changing matching or source-selection policy.
 * Unknown match labels fail closed as unmatched so a future label cannot inflate coverage.
 */
internal object EpgMatchDiagnosticsPolicy {
    fun summarize(
        playlistId: Long,
        epgSourceUrl: String,
        sourceLoadedAtMs: Long?,
        observations: List<EpgMatchObservation>
    ): PlaylistEpgDiagnostics {
        var tvgIdMatches = 0
        var displayNameMatches = 0
        var channelIdMatches = 0
        var channelsWithPrograms = 0

        observations.forEach { observation ->
            val isMatched = when (observation.matchedBy) {
                MATCH_TVG_ID -> {
                    tvgIdMatches += 1
                    true
                }
                MATCH_DISPLAY_NAME -> {
                    displayNameMatches += 1
                    true
                }
                MATCH_CHANNEL_ID -> {
                    channelIdMatches += 1
                    true
                }
                else -> false
            }
            if (isMatched && observation.hasPrograms) channelsWithPrograms += 1
        }

        val matchedChannels = tvgIdMatches + displayNameMatches + channelIdMatches
        return PlaylistEpgDiagnostics(
            playlistId = playlistId,
            epgSourceUrl = epgSourceUrl,
            sourceLoadedAtMs = sourceLoadedAtMs,
            totalChannels = observations.size,
            matchedChannels = matchedChannels,
            unmatchedChannels = observations.size - matchedChannels,
            tvgIdMatches = tvgIdMatches,
            displayNameMatches = displayNameMatches,
            channelIdMatches = channelIdMatches,
            channelsWithPrograms = channelsWithPrograms
        )
    }

    const val MATCH_TVG_ID = "tvg-id"
    const val MATCH_DISPLAY_NAME = "display-name"
    const val MATCH_CHANNEL_ID = "channel-id"
}
