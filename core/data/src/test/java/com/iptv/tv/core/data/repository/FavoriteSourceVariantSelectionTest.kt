package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import com.iptv.tv.core.model.ChannelHealth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FavoriteSourceVariantSelectionTest {
    @Test
    fun changedLiveStreamRetargetsPersistedPreferredVariantWithoutChangingCompatibilityId() {
        val oldUrl = "https://old.example/live"
        val newUrl = "https://new.example/live"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = oldUrl)
        val saved = variant(channelId = 11, playlistId = 1, url = oldUrl)
        val refreshedLive = channel(id = 11, playlistId = 1, url = newUrl)
        val source = playlist(
            id = 1,
            name = "Ready source",
            sourceType = "URL",
            catalogOrigin = "READY_CATALOG"
        )

        val result = FavoriteSourceVariantSelection.reconcileChangedLiveVariants(
            favorite = favorite,
            persistedVariants = listOf(saved),
            liveChannels = listOf(refreshedLive),
            playlists = mapOf(1L to source),
            updatedAt = 700
        )

        assertEquals(11L, result.favorite.preferredChannelId)
        assertEquals(newUrl, result.favorite.preferredStreamUrl)
        assertEquals(700L, result.favorite.updatedAt)
        assertEquals(1, result.upsertVariants.size)
        assertEquals(newUrl, result.upsertVariants.single().streamUrl)
        assertEquals(UnifiedFavoritePersistence.variantKey(newUrl), result.variants.single().variantKey)
        assertEquals(setOf(UnifiedFavoritePersistence.variantKey(oldUrl)), result.obsoleteVariantKeys)
    }

    @Test
    fun refreshingCompatibilityRowDoesNotOverrideExplicitlyPreferredDifferentSource() {
        val compatibilityOldUrl = "https://source-a.example/old"
        val compatibilityNewUrl = "https://source-a.example/new"
        val explicitPreferredUrl = "https://source-b.example/live"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = explicitPreferredUrl)
        val compatibilitySaved = variant(channelId = 11, playlistId = 1, url = compatibilityOldUrl)
        val explicitSaved = variant(channelId = 22, playlistId = 2, url = explicitPreferredUrl)
        val refreshedCompatibility = channel(id = 11, playlistId = 1, url = compatibilityNewUrl)

        val result = FavoriteSourceVariantSelection.reconcileChangedLiveVariants(
            favorite = favorite,
            persistedVariants = listOf(compatibilitySaved, explicitSaved),
            liveChannels = listOf(refreshedCompatibility),
            playlists = emptyMap(),
            updatedAt = 800
        )

        assertEquals(explicitPreferredUrl, result.favorite.preferredStreamUrl)
        assertEquals(2L, result.favorite.updatedAt)
        assertTrue(result.variants.any { it.streamUrl == compatibilityNewUrl })
        assertTrue(result.variants.any { it.streamUrl == explicitPreferredUrl })
    }

    @Test
    fun missingLiveVariantsAddsOnlyUnseenSourceAndCopiesProvenance() {
        val firstUrl = "https://one.example/live"
        val secondUrl = "https://two.example/live"
        val favorite = favorite(preferredUrl = firstUrl)
        val persisted = variant(channelId = 11, playlistId = 1, url = firstUrl)
        val firstLive = channel(id = 11, playlistId = 1, url = firstUrl)
        val secondLive = channel(id = 22, playlistId = 2, url = secondUrl)
        val secondPlaylist = playlist(
            id = 2,
            name = "Local backup",
            sourceType = "FILE",
            catalogOrigin = "LOCAL"
        )

        val additions = FavoriteSourceVariantSelection.missingLiveVariants(
            favorite = favorite,
            persistedVariants = listOf(persisted),
            liveChannels = listOf(firstLive, secondLive),
            playlists = mapOf(2L to secondPlaylist),
            discoveredAt = 500
        )

        assertEquals(1, additions.size)
        val added = additions.single()
        assertEquals(UnifiedFavoritePersistence.variantKey(secondUrl), added.variantKey)
        assertEquals(22L, added.legacyChannelId)
        assertEquals(2L, added.playlistId)
        assertEquals("Local backup", added.playlistName)
        assertEquals("FILE", added.sourceType)
        assertEquals("LOCAL", added.catalogOrigin)
        assertEquals(500L, added.addedAt)
        assertEquals(500L, added.updatedAt)
    }

    @Test
    fun sourceListDeduplicatesPersistedAndLiveVariantAndMarksPreferredFirst() {
        val firstUrl = "https://one.example/live"
        val secondUrl = "https://two.example/live"
        val favorite = favorite(preferredUrl = secondUrl)
        val firstSaved = variant(channelId = 11, playlistId = 1, url = firstUrl)
        val secondSaved = variant(channelId = 22, playlistId = 2, url = secondUrl)
        val secondLive = channel(id = 220, playlistId = 20, url = secondUrl)
        val currentPlaylist = playlist(
            id = 20,
            name = "Current provider",
            sourceType = "URL",
            catalogOrigin = "USER_IMPORT"
        )

        val result = FavoriteSourceVariantSelection.buildSourceVariants(
            favorite = favorite,
            persistedVariants = listOf(firstSaved, secondSaved),
            liveChannels = listOf(secondLive),
            playlists = mapOf(20L to currentPlaylist)
        )

        assertEquals(2, result.size)
        val preferred = result.first()
        assertTrue(preferred.isPreferred)
        assertTrue(preferred.isLive)
        assertEquals(ChannelHealth.AVAILABLE, preferred.health)
        assertEquals("Current provider", preferred.playlistName)
        assertEquals(secondUrl, preferred.streamUrl)
        assertFalse(result[1].isPreferred)
    }

    @Test
    fun selectingPreferredSourceKeepsAggregateCompatibilityIdStable() {
        val firstUrl = "https://one.example/live"
        val secondUrl = "https://two.example/live"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = firstUrl)
        val secondLive = channel(id = 22, playlistId = 2, url = secondUrl)
        val secondSaved = variant(channelId = 22, playlistId = 2, url = secondUrl)

        val updated = FavoriteSourceVariantSelection.selectPreferredSource(
            favorite = favorite,
            variantKey = UnifiedFavoritePersistence.variantKey(secondUrl),
            persistedVariants = listOf(secondSaved),
            liveChannels = listOf(secondLive),
            updatedAt = 900
        )

        requireNotNull(updated)
        assertEquals(11L, updated.preferredChannelId)
        assertEquals(2L, updated.preferredPlaylistId)
        assertEquals(secondUrl, updated.preferredStreamUrl)
        assertEquals(900L, updated.updatedAt)
    }

    @Test
    fun aggregatePlaybackUsesExplicitPreferredPersistedSourceOverOtherLiveVariant() {
        val liveUrl = "https://live.example/source-a"
        val savedUrl = "https://saved.example/source-b"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = savedUrl)
        val live = channel(id = 11, playlistId = 1, url = liveUrl)
        val savedPreferred = variant(channelId = 22, playlistId = 2, url = savedUrl)

        val result = FavoriteSourceVariantSelection.resolvePlaybackContext(
            requestedChannelId = 11,
            favorite = favorite,
            persistedVariants = listOf(savedPreferred),
            liveChannels = listOf(live)
        )

        assertFalse(result.isLiveVariant)
        assertEquals(savedUrl, result.channel.streamUrl)
        assertEquals(2L, result.channel.playlistId)
        assertEquals(2, result.availableVariantCount)
    }

    @Test
    fun explicitNonAggregateLiveRequestStillWinsOverStoredPreference() {
        val preferredUrl = "https://preferred.example/live"
        val requestedUrl = "https://requested.example/live"
        val favorite = favorite(preferredChannelId = 11, preferredUrl = preferredUrl)
        val preferred = channel(id = 11, playlistId = 1, url = preferredUrl)
        val requested = channel(id = 22, playlistId = 2, url = requestedUrl)

        val result = FavoriteSourceVariantSelection.resolvePlaybackContext(
            requestedChannelId = 22,
            favorite = favorite,
            persistedVariants = emptyList(),
            liveChannels = listOf(preferred, requested)
        )

        assertTrue(result.isLiveVariant)
        assertEquals(22L, result.channel.id)
        assertEquals(requestedUrl, result.channel.streamUrl)
    }

    private fun favorite(
        preferredChannelId: Long = 11,
        preferredUrl: String
    ): FavoriteChannelEntity = FavoriteChannelEntity(
        logicalKey = LOGICAL_KEY,
        tvgId = "news.one",
        name = "News One",
        groupName = "Новости",
        logo = null,
        preferredStreamUrl = preferredUrl,
        preferredPlaylistId = 1,
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
        url: String
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = LOGICAL_KEY,
        variantKey = UnifiedFavoritePersistence.variantKey(url),
        legacyChannelId = channelId,
        playlistId = playlistId,
        playlistName = "Saved $playlistId",
        sourceType = "URL",
        catalogOrigin = "USER_IMPORT",
        tvgId = "news.one",
        name = "News One",
        groupName = "Новости",
        logo = null,
        streamUrl = url,
        addedAt = 1,
        updatedAt = 2
    )

    private fun playlist(
        id: Long,
        name: String,
        sourceType: String,
        catalogOrigin: String
    ): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        sourceType = sourceType,
        source = "source-$id",
        epgSourceUrl = null,
        scheduleHours = 0,
        lastSyncedAt = null,
        isCustom = true,
        createdAt = 1,
        catalogOrigin = catalogOrigin
    )

    private companion object {
        const val LOGICAL_KEY = "tvg:news.one"
    }
}
