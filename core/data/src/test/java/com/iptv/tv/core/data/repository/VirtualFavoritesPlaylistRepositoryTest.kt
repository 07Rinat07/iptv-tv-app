package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
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
    fun persistedPlaybackVariantReplacesStaleSnapshotUrlButKeepsAggregateId() {
        val favorite = FavoriteChannelEntity(
            logicalKey = "tvg:news.one",
            tvgId = "news.one",
            name = "News One",
            groupName = "Новости",
            logo = null,
            preferredStreamUrl = "https://stale.example/live",
            preferredPlaylistId = 7,
            preferredChannelId = 11,
            addedAt = 1,
            updatedAt = 2
        )
        val recoveredVariant = FavoriteChannelVariantEntity(
            logicalKey = favorite.logicalKey,
            variantKey = UnifiedFavoritePersistence.variantKey("https://recovered.example/live"),
            legacyChannelId = 22,
            playlistId = 8,
            playlistName = "Recovered source",
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT",
            tvgId = favorite.tvgId,
            name = favorite.name,
            groupName = favorite.groupName,
            logo = null,
            streamUrl = "https://recovered.example/live",
            addedAt = 1,
            updatedAt = 100
        )

        val result = resolvedFavoriteRepresentatives(
            favorites = listOf(favorite),
            persistedVariants = listOf(recoveredVariant),
            liveChannels = emptyList()
        ).single()

        assertEquals(11L, result.id)
        assertEquals(8L, result.playlistId)
        assertEquals("https://recovered.example/live", result.streamUrl)
    }

    @Test
    fun representativeIdsKeepStableFavoriteIdAlongsideLiveVariants() {
        val result = favoriteRepresentativeIds(
            liveIds = linkedSetOf(101L, 202L),
            representativeIds = listOf(202L, 303L)
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
