package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedFavoritePlaybackContextTest {
    @Test
    fun requestedLiveVariantWinsOverPreferredAndPersistedVariants() {
        val favorite = favorite(preferredChannelId = 11, preferredUrl = "https://preferred.example/live")
        val preferredLive = channel(id = 11, playlistId = 1, url = "https://preferred.example/live")
        val requestedLive = channel(id = 22, playlistId = 2, url = "https://requested.example/live")
        val persisted = variant(
            channelId = 33,
            playlistId = 3,
            url = "https://saved.example/live",
            updatedAt = 500
        )

        val result = UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = 22,
            favorite = favorite,
            persistedVariants = listOf(persisted),
            liveChannels = listOf(preferredLive, requestedLive)
        )

        assertTrue(result.isLiveVariant)
        assertEquals(22L, result.channel.id)
        assertEquals("https://requested.example/live", result.channel.streamUrl)
        assertEquals(3, result.availableVariantCount)
    }

    @Test
    fun preferredPersistedVariantRestoresPlaybackWhenEveryLiveRowWasDeleted() {
        val favorite = favorite(preferredChannelId = 11, preferredUrl = "https://preferred.example/live")
        val preferredSaved = variant(
            channelId = 11,
            playlistId = 7,
            url = "https://preferred.example/live",
            updatedAt = 100
        )
        val newerOtherSaved = variant(
            channelId = 22,
            playlistId = 8,
            url = "https://other.example/live",
            updatedAt = 900
        )

        val result = UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = 11,
            favorite = favorite,
            persistedVariants = listOf(newerOtherSaved, preferredSaved),
            liveChannels = emptyList()
        )

        assertFalse(result.isLiveVariant)
        assertEquals(11L, result.channel.id)
        assertEquals(7L, result.channel.playlistId)
        assertEquals("https://preferred.example/live", result.channel.streamUrl)
        assertEquals(2, result.availableVariantCount)
    }

    @Test
    fun snapshotFallbackRemainsPlayableWithoutLiveOrPersistedVariantRows() {
        val favorite = favorite(preferredChannelId = 44, preferredUrl = "https://snapshot.example/live")

        val result = UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = 44,
            favorite = favorite,
            persistedVariants = emptyList(),
            liveChannels = emptyList()
        )

        assertFalse(result.isLiveVariant)
        assertEquals(44L, result.channel.id)
        assertEquals("News One", result.channel.name)
        assertEquals("https://snapshot.example/live", result.channel.streamUrl)
        assertEquals(ChannelHealth.UNKNOWN, result.channel.health)
        assertEquals(1, result.availableVariantCount)
    }

    @Test
    fun variantCountDeduplicatesSameStreamAcrossLiveAndPersistedSources() {
        val sharedUrl = "https://shared.example/live"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = sharedUrl)
        val live = channel(id = 11, playlistId = 1, url = sharedUrl)
        val savedSame = variant(channelId = 11, playlistId = 1, url = sharedUrl, updatedAt = 100)
        val savedOther = variant(
            channelId = 22,
            playlistId = 2,
            url = "https://other.example/live",
            updatedAt = 200
        )

        val result = UnifiedFavoritePersistence.resolvePlaybackContext(
            requestedChannelId = 11,
            favorite = favorite,
            persistedVariants = listOf(savedSame, savedOther),
            liveChannels = listOf(live)
        )

        assertEquals(2, result.availableVariantCount)
    }

    private fun favorite(
        preferredChannelId: Long,
        preferredUrl: String
    ): FavoriteChannelEntity = FavoriteChannelEntity(
        logicalKey = LOGICAL_KEY,
        tvgId = "news.one",
        name = "News One",
        groupName = "Новости",
        logo = null,
        preferredStreamUrl = preferredUrl,
        preferredPlaylistId = 7,
        preferredChannelId = preferredChannelId,
        addedAt = 1,
        updatedAt = 2
    )

    private fun channel(
        id: Long,
        playlistId: Long,
        url: String
    ): ChannelEntity = ChannelEntity(
        id = id,
        playlistId = playlistId,
        tvgId = "news.one",
        name = "News One",
        groupName = "Новости",
        logo = null,
        streamUrl = url,
        health = ChannelHealth.AVAILABLE.name,
        orderIndex = id.toInt(),
        isHidden = false
    )

    private fun variant(
        channelId: Long,
        playlistId: Long,
        url: String,
        updatedAt: Long
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = LOGICAL_KEY,
        variantKey = UnifiedFavoritePersistence.variantKey(url),
        legacyChannelId = channelId,
        playlistId = playlistId,
        playlistName = "Source $playlistId",
        sourceType = "URL",
        catalogOrigin = "USER_IMPORT",
        tvgId = "news.one",
        name = "News One",
        groupName = "Новости",
        logo = null,
        streamUrl = url,
        addedAt = 1,
        updatedAt = updatedAt
    )

    private companion object {
        const val LOGICAL_KEY = "tvg:news.one"
    }
}
