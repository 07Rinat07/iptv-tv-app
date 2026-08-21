package com.iptv.tv.core.data.repository

import com.iptv.tv.core.model.CatalogOriginKind
import com.iptv.tv.core.model.Channel
import com.iptv.tv.core.model.ChannelHealth
import com.iptv.tv.core.model.PlaylistSourceType
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_PLAYLIST_ID
import com.iptv.tv.core.model.VIRTUAL_FAVORITES_SOURCE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualFavoritesPlaylistRepositoryTest {
    @Test
    fun virtualPlaylistUsesStableSystemIdentityWithoutRoomRow() {
        val playlist = virtualFavoritesPlaylist(channelCount = 3)

        assertEquals(VIRTUAL_FAVORITES_PLAYLIST_ID, playlist.id)
        assertTrue(playlist.id < 0)
        assertEquals("Избранное", playlist.name)
        assertEquals(VIRTUAL_FAVORITES_SOURCE, playlist.source)
        assertEquals(PlaylistSourceType.CUSTOM, playlist.sourceType)
        assertEquals(CatalogOriginKind.SYSTEM, playlist.catalogOrigin)
        assertEquals(3, playlist.channelCount)
    }

    @Test
    fun virtualSummaryPreservesAggregateCountsAndGroups() {
        val channels = listOf(
            channel(10, "News One", "Новости", ChannelHealth.AVAILABLE, tvgId = "news.one", logo = "logo"),
            channel(11, "News Two", "Новости", ChannelHealth.UNKNOWN),
            channel(12, "Sport", "Спорт", ChannelHealth.UNSTABLE)
        )

        val summary = virtualFavoritesSummary(channels)

        assertEquals(VIRTUAL_FAVORITES_PLAYLIST_ID, summary.playlistId)
        assertEquals(3, summary.totalChannels)
        assertEquals(3, summary.visibleChannels)
        assertEquals(1, summary.availableChannels)
        assertEquals(1, summary.unstableChannels)
        assertEquals(1, summary.unknownHealthChannels)
        assertEquals(2, summary.groupCount)
        assertEquals("Новости" to 2, summary.topGroups.first())
        assertEquals(1, summary.channelsWithLogo)
        assertEquals(1, summary.channelsWithTvgId)
    }

    @Test
    fun representativeIdsKeepOrphanFavoriteMarkedAlongsideLiveVariants() {
        val result = favoriteRepresentativeIds(
            liveIds = linkedSetOf(101L, 202L),
            representatives = listOf(
                channel(202, "Live", "General", ChannelHealth.AVAILABLE),
                channel(303, "Orphan snapshot", "General", ChannelHealth.UNKNOWN)
            )
        )

        assertEquals(linkedSetOf(101L, 202L, 303L), result)
    }

    private fun channel(
        id: Long,
        name: String,
        group: String,
        health: ChannelHealth,
        tvgId: String? = null,
        logo: String? = null
    ): Channel = Channel(
        id = id,
        playlistId = id + 1_000,
        tvgId = tvgId,
        name = name,
        group = group,
        logo = logo,
        streamUrl = "https://example.com/$id.m3u8",
        health = health,
        orderIndex = id.toInt(),
        isHidden = false
    )
}
