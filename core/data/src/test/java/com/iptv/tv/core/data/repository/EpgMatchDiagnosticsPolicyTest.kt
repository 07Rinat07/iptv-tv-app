package com.iptv.tv.core.data.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class EpgMatchDiagnosticsPolicyTest {
    @Test
    fun summarizeCountsKnownMatchKindsAndKeepsProgramCoverageSeparate() {
        val result = EpgMatchDiagnosticsPolicy.summarize(
            playlistId = 42,
            epgSourceUrl = "https://epg.example/guide.xml",
            sourceLoadedAtMs = 1_234L,
            observations = listOf(
                EpgMatchObservation("tvg-id", hasPrograms = true),
                EpgMatchObservation("display-name", hasPrograms = false),
                EpgMatchObservation("channel-id", hasPrograms = true),
                EpgMatchObservation("not-matched", hasPrograms = false)
            )
        )

        assertEquals(42L, result.playlistId)
        assertEquals("https://epg.example/guide.xml", result.epgSourceUrl)
        assertEquals(1_234L, result.sourceLoadedAtMs)
        assertEquals(4, result.totalChannels)
        assertEquals(3, result.matchedChannels)
        assertEquals(1, result.unmatchedChannels)
        assertEquals(1, result.tvgIdMatches)
        assertEquals(1, result.displayNameMatches)
        assertEquals(1, result.channelIdMatches)
        assertEquals(2, result.channelsWithPrograms)
    }

    @Test
    fun summarizeTreatsUnknownLabelsAsUnmatched() {
        val result = EpgMatchDiagnosticsPolicy.summarize(
            playlistId = 7,
            epgSourceUrl = "https://epg.example/guide.xml",
            sourceLoadedAtMs = null,
            observations = listOf(
                EpgMatchObservation("future-fuzzy-match", hasPrograms = true),
                EpgMatchObservation("not-matched", hasPrograms = false)
            )
        )

        assertEquals(2, result.totalChannels)
        assertEquals(0, result.matchedChannels)
        assertEquals(2, result.unmatchedChannels)
        assertEquals(0, result.channelsWithPrograms)
    }

    @Test
    fun summarizeSupportsEmptyVisiblePlaylist() {
        val result = EpgMatchDiagnosticsPolicy.summarize(
            playlistId = 9,
            epgSourceUrl = "https://epg.example/guide.xml",
            sourceLoadedAtMs = 99L,
            observations = emptyList()
        )

        assertEquals(0, result.totalChannels)
        assertEquals(0, result.matchedChannels)
        assertEquals(0, result.unmatchedChannels)
        assertEquals(0, result.channelsWithPrograms)
    }
}
