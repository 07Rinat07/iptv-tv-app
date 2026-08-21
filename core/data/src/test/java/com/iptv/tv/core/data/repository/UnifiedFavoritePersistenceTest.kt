package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteLegacySeedEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedFavoritePersistenceTest {
    @Test
    fun legacySeeds_withSameLogicalIdentityBecomeOneFavoriteWithTwoVariants() {
        val seeds = listOf(
            seed(
                channelId = 10,
                playlistId = 1,
                playlistName = "A",
                tvgId = " discovery.hd ",
                name = "Discovery HD",
                url = "https://one.example/live",
                addedAt = 100
            ),
            seed(
                channelId = 20,
                playlistId = 2,
                playlistName = "B",
                tvgId = "discovery.hd",
                name = "Discovery Channel",
                url = "https://two.example/live",
                addedAt = 200
            )
        )

        val result = UnifiedFavoritePersistence.fromLegacySeeds(seeds, updatedAt = 500)

        assertEquals(1, result.favorites.size)
        assertEquals(2, result.variants.size)
        val favorite = result.favorites.single()
        assertEquals("tvg:discovery.hd", favorite.logicalKey)
        assertEquals(10L, favorite.preferredChannelId)
        assertEquals(1L, favorite.preferredPlaylistId)
        assertEquals(100L, favorite.addedAt)
        assertEquals(500L, favorite.updatedAt)
    }

    @Test
    fun representFavorites_keepsSnapshotWhenSourceChannelWasDeleted() {
        val favorite = FavoriteChannelEntity(
            logicalKey = "tvg:news.one",
            tvgId = "news.one",
            name = "News One",
            groupName = "Новости",
            logo = "https://img/logo.png",
            preferredStreamUrl = "https://saved.example/live",
            preferredPlaylistId = 44,
            preferredChannelId = 4401,
            addedAt = 1,
            updatedAt = 2
        )

        val represented = UnifiedFavoritePersistence.representFavorites(
            favorites = listOf(favorite),
            liveChannels = emptyList()
        ).single()

        assertEquals(4401L, represented.id)
        assertEquals(44L, represented.playlistId)
        assertEquals("News One", represented.name)
        assertEquals("https://saved.example/live", represented.streamUrl)
        assertEquals(ChannelHealth.UNKNOWN, represented.health)
    }

    @Test
    fun favoriteLiveChannelIds_marksEveryCurrentVariantOfLogicalFavorite() {
        val favorite = FavoriteChannelEntity(
            logicalKey = "name:match tv",
            tvgId = null,
            name = "Матч ТВ",
            groupName = "Спорт",
            logo = null,
            preferredStreamUrl = "https://a/live",
            preferredPlaylistId = 1,
            preferredChannelId = 11,
            addedAt = 1,
            updatedAt = 1
        )
        val channels = listOf(
            channel(id = 11, playlistId = 1, tvgId = null, name = "Матч! ТВ HD", url = "https://a/live"),
            channel(id = 22, playlistId = 2, tvgId = null, name = "Матч ТВ", url = "https://b/live"),
            channel(id = 33, playlistId = 3, tvgId = null, name = "Другой канал", url = "https://c/live")
        )

        val ids = UnifiedFavoritePersistence.favoriteLiveChannelIds(
            favorites = listOf(favorite),
            liveChannels = channels
        )

        assertEquals(setOf(11L, 22L), ids)
    }

    @Test
    fun fromLiveChannels_copiesPlaylistProvenanceIntoIndependentVariants() {
        val preferred = channel(
            id = 11,
            playlistId = 7,
            tvgId = "sport.one",
            name = "Sport One",
            url = "https://a/live"
        )
        val other = channel(
            id = 12,
            playlistId = 8,
            tvgId = "sport.one",
            name = "Sport One HD",
            url = "https://b/live"
        )
        val playlists = mapOf(
            7L to playlist(7, "Ready", "READY_CATALOG"),
            8L to playlist(8, "Scanner", "SCANNER_IMPORT")
        )

        val batch = UnifiedFavoritePersistence.fromLiveChannels(
            logicalKey = "tvg:sport.one",
            preferred = preferred,
            equivalents = listOf(preferred, other),
            playlists = playlists,
            addedAt = 100,
            updatedAt = 100
        )

        assertEquals(2, batch.variants.size)
        assertTrue(batch.variants.any { it.playlistName == "Ready" && it.catalogOrigin == "READY_CATALOG" })
        assertTrue(batch.variants.any { it.playlistName == "Scanner" && it.catalogOrigin == "SCANNER_IMPORT" })
    }

    @Test
    fun variantKey_isStableForSameUrlAndDifferentForOtherUrl() {
        val first = UnifiedFavoritePersistence.variantKey(" https://example.com/live ")
        val same = UnifiedFavoritePersistence.variantKey("https://example.com/live")
        val other = UnifiedFavoritePersistence.variantKey("https://example.com/other")

        assertEquals(first, same)
        assertNotEquals(first, other)
    }

    private fun seed(
        channelId: Long,
        playlistId: Long,
        playlistName: String,
        tvgId: String?,
        name: String,
        url: String,
        addedAt: Long
    ): FavoriteLegacySeedEntity = FavoriteLegacySeedEntity(
        legacyChannelId = channelId,
        playlistId = playlistId,
        playlistName = playlistName,
        sourceType = "URL",
        catalogOrigin = "USER_IMPORT",
        tvgId = tvgId,
        name = name,
        groupName = "Group",
        logo = null,
        streamUrl = url,
        addedAt = addedAt
    )

    private fun channel(
        id: Long,
        playlistId: Long,
        tvgId: String?,
        name: String,
        url: String
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = tvgId,
        name = name,
        groupName = "Group",
        logo = null,
        streamUrl = url,
        health = ChannelHealth.UNKNOWN.name,
        orderIndex = 0,
        isHidden = false
    )

    private fun playlist(id: Long, name: String, origin: String): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        sourceType = "URL",
        source = "https://example.com/$id.m3u",
        epgSourceUrl = null,
        scheduleHours = 12,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = id,
        catalogOrigin = origin
    )
}
