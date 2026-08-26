package com.iptv.tv.sync.worker

import com.iptv.tv.core.model.EpgUserSettings
import com.iptv.tv.core.model.Playlist
import com.iptv.tv.core.model.PlaylistSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpgRefreshWorkerPolicyTest {
    @Test
    fun eligibleEpgRefreshPlaylistsExcludesSystemVirtualAggregates() {
        val physicalA = playlist(id = 1L, name = "A")
        val virtualAll = playlist(id = -100L, name = "All")
        val physicalB = playlist(id = 2L, name = "B")
        val virtualRecent = playlist(id = -300L, name = "Recent")

        val eligible = eligibleEpgRefreshPlaylists(
            listOf(physicalA, virtualAll, physicalB, virtualRecent)
        )

        assertEquals(listOf(1L, 2L), eligible.map { it.id })
    }

    @Test
    fun freshnessGateSkipsDuplicateAutomaticWorkButHonorsForcedRefresh() {
        val now = 2_000_000_000L
        val fresh = EpgUserSettings(
            refreshIntervalHours = 24,
            lastSuccessfulRefreshAtMs = now - 60L * 60L * 1_000L
        )
        val stale = fresh.copy(
            lastSuccessfulRefreshAtMs = now - 25L * 60L * 60L * 1_000L
        )

        assertFalse(shouldRunEpgRefresh(forceRefresh = false, settings = fresh, nowMs = now))
        assertTrue(shouldRunEpgRefresh(forceRefresh = false, settings = stale, nowMs = now))
        assertTrue(shouldRunEpgRefresh(forceRefresh = true, settings = fresh, nowMs = now))
    }

    private fun playlist(id: Long, name: String): Playlist = Playlist(
        id = id,
        name = name,
        sourceType = PlaylistSourceType.URL,
        source = "https://example.com/$name.m3u8",
        scheduleHours = 0,
        lastSyncedAt = null,
        channelCount = 1,
        isCustom = false
    )
}
