package com.iptv.tv.core.data.repository

import com.iptv.tv.core.database.dao.FavoriteChannelLookupDao
import com.iptv.tv.core.database.dao.FavoriteDao
import com.iptv.tv.core.database.dao.FavoriteSnapshotDao
import com.iptv.tv.core.database.dao.PlaylistDao
import com.iptv.tv.core.database.entity.ChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelEntity
import com.iptv.tv.core.database.entity.FavoriteChannelVariantEntity
import com.iptv.tv.core.database.entity.PlaylistEntity
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FavoriteSourceVariantServicePlaylistMetadataBatchTest {
    @Test
    fun sourceVariantsBatchPlaylistMetadataForLiveVariants() = runTest {
        val favoriteSnapshotDao = mockk<FavoriteSnapshotDao>(relaxed = true)
        val favoriteChannelLookupDao = mockk<FavoriteChannelLookupDao>(relaxed = true)
        val favoriteLiveChannelResolver = mockk<FavoriteLiveChannelResolver>()
        val favoriteDao = mockk<FavoriteDao>(relaxed = true)
        val playlistDao = mockk<PlaylistDao>(relaxed = true)
        val firstChannel = channel(
            id = 101L,
            playlistId = 7L,
            streamUrl = "https://one.example/live.m3u8"
        )
        val secondChannel = channel(
            id = 102L,
            playlistId = 8L,
            streamUrl = "https://two.example/live.m3u8"
        )
        val favorite = FavoriteChannelEntity(
            logicalKey = "tvg:news.one",
            tvgId = "news.one",
            name = "News One",
            groupName = "News",
            logo = null,
            preferredStreamUrl = firstChannel.streamUrl,
            preferredPlaylistId = firstChannel.playlistId,
            preferredChannelId = 77L,
            addedAt = 1L,
            updatedAt = 2L
        )
        val persistedVariants = listOf(
            variant(favorite.logicalKey, firstChannel),
            variant(favorite.logicalKey, secondChannel)
        )

        coEvery { favoriteChannelLookupDao.findChannelById(77L) } returns null
        coEvery { favoriteSnapshotDao.findFavoriteByPreferredChannelId(77L) } returns favorite
        coEvery {
            favoriteLiveChannelResolver.findMatchingChannels(setOf(favorite.logicalKey))
        } returns listOf(firstChannel, secondChannel)
        coEvery { playlistDao.findByIds(listOf(7L, 8L)) } returns listOf(
            playlist(id = 7L, name = "Playlist 7"),
            playlist(id = 8L, name = "Playlist 8")
        )
        coEvery { favoriteSnapshotDao.getVariants(favorite.logicalKey) } returns persistedVariants

        val service = FavoriteSourceVariantService(
            favoriteSnapshotDao = favoriteSnapshotDao,
            favoriteChannelLookupDao = favoriteChannelLookupDao,
            favoriteLiveChannelResolver = favoriteLiveChannelResolver,
            favoriteDao = favoriteDao,
            playlistDao = playlistDao
        )

        val result = service.getSourceVariants(77L)

        assertEquals(2, result.size)
        assertEquals(
            setOf("Playlist 7", "Playlist 8"),
            result.mapNotNull { variant -> variant.playlistName }.toSet()
        )
        coVerify(exactly = 1) { playlistDao.findByIds(listOf(7L, 8L)) }
        coVerify(exactly = 0) { playlistDao.findById(any()) }
    }

    private fun channel(id: Long, playlistId: Long, streamUrl: String): ChannelEntity =
        ChannelEntity(
            id = id,
            playlistId = playlistId,
            tvgId = "news.one",
            name = "News One",
            groupName = "News",
            logo = null,
            streamUrl = streamUrl,
            health = "AVAILABLE",
            orderIndex = 0,
            isHidden = false
        )

    private fun variant(
        logicalKey: String,
        channel: ChannelEntity
    ): FavoriteChannelVariantEntity = FavoriteChannelVariantEntity(
        logicalKey = logicalKey,
        variantKey = UnifiedFavoritePersistence.variantKey(channel.streamUrl),
        legacyChannelId = channel.id,
        playlistId = channel.playlistId,
        playlistName = null,
        sourceType = null,
        catalogOrigin = null,
        tvgId = channel.tvgId,
        name = channel.name,
        groupName = channel.groupName,
        logo = channel.logo,
        streamUrl = channel.streamUrl,
        addedAt = 1L,
        updatedAt = 2L
    )

    private fun playlist(id: Long, name: String): PlaylistEntity = PlaylistEntity(
        id = id,
        name = name,
        sourceType = "URL",
        source = "https://playlist.example/$id.m3u8",
        epgSourceUrl = null,
        scheduleHours = 0,
        lastSyncedAt = null,
        isCustom = false,
        createdAt = id,
        catalogOrigin = "USER_IMPORT"
    )
}
